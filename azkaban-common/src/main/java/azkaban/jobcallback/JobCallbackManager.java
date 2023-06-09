package azkaban.jobcallback;

import static azkaban.Constants.ConfigurationKeys.AZKABAN_WEBSERVER_URL;
import static azkaban.Constants.ConfigurationKeys.JETTY_HOSTNAME;
import static azkaban.Constants.ConfigurationKeys.JETTY_PORT;
import static azkaban.Constants.ConfigurationKeys.JETTY_SSL_PORT;
import static azkaban.Constants.ConfigurationKeys.JETTY_USE_SSL;
import static azkaban.Constants.DEFAULT_PORT_NUMBER;
import static azkaban.Constants.DEFAULT_SSL_PORT_NUMBER;
import static azkaban.jobcallback.JobCallbackConstants.CONTEXT_JOB_TOKEN;
import static azkaban.jobcallback.JobCallbackStatusEnum.COMPLETED;
import static azkaban.jobcallback.JobCallbackStatusEnum.FAILURE;
import static azkaban.jobcallback.JobCallbackStatusEnum.STARTED;
import static azkaban.jobcallback.JobCallbackStatusEnum.SUCCESS;

import azkaban.event.Event;
import azkaban.event.EventData;
import azkaban.event.EventListener;
import azkaban.executor.JobRunnerBase;
import azkaban.jmx.JmxJobCallback;
import azkaban.jmx.JmxJobCallbackMBean;
import azkaban.executor.Status;
import azkaban.spi.EventType;
import azkaban.utils.Props;
import azkaban.utils.PropsUtils;
import java.net.InetAddress;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.message.BasicHeader;
import org.apache.log4j.Logger;

/**
 * Responsible processing job callback properties on job status change events.
 *
 * When job callback properties are specified, they will be converted to HTTP calls to execute. The
 * HTTP requests will be made in asynchronous mode so the caller to the handleEvent method will not
 * be block. In addition, the HTTP calls will be configured to time appropriately for connection
 * request, creating connection, and socket timeout.
 *
 * The HTTP request and response will be logged out the job's log for debugging and traceability
 * purpose.
 *
 * @author hluu
 */
public class JobCallbackManager implements EventListener<Event> {

  private static final Logger logger = Logger.getLogger(JobCallbackManager.class);
  private static final JobCallbackStatusEnum[] ON_COMPLETION_JOB_CALLBACK_STATUS =
      {SUCCESS, FAILURE, COMPLETED};
  private static boolean isInitialized = false;
  private static JobCallbackManager instance;
  private static int maxNumCallBack = 3;
  private final JmxJobCallbackMBean callbackMbean;
  private final String azkabanHostName;
  private final SimpleDateFormat gmtDateFormatter;

  private JobCallbackManager(final Props props) {
    maxNumCallBack = props.getInt("jobcallback.max_count", maxNumCallBack);

    // initialize the request maker
    JobCallbackRequestMaker.initialize(props);

    this.callbackMbean =
        new JmxJobCallback(JobCallbackRequestMaker.getInstance()
            .getJobcallbackMetrics());

    this.azkabanHostName = getAzkabanHostName(props);

    this.gmtDateFormatter = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
    this.gmtDateFormatter.setTimeZone(TimeZone.getTimeZone("GMT"));

    logger.info("Initialization completed " + getClass().getName());
    logger.info("azkabanHostName " + this.azkabanHostName);
  }

  public static void initialize(final Props props) {
    if (isInitialized) {
      logger.info("Already initialized");
      return;
    }

    logger.info("Initializing");
    instance = new JobCallbackManager(props);

    isInitialized = true;
  }

  public static boolean isInitialized() {
    return isInitialized;
  }

  public static JobCallbackManager getInstance() {
    if (!isInitialized) {
      throw new IllegalStateException(JobCallbackManager.class.getName()
          + " has not been initialized");
    }
    return instance;
  }

  public JmxJobCallbackMBean getJmxJobCallbackMBean() {
    return this.callbackMbean;
  }

  @Override
  public void handleEvent(final Event event) {
    if (!isInitialized) {
      return;
    }

    if (event.getRunner() instanceof JobRunnerBase) {
      JobRunnerBase jobRunnerBase = (JobRunnerBase) event.getRunner();
      Props jobProps = jobRunnerBase.getProps();
      Logger jobLogger = jobRunnerBase.getLogger();
      try {
        if (event.getType() == EventType.JOB_STARTED) {
          processJobCallOnStart(event, jobProps, jobLogger);
        } else if (event.getType() == EventType.JOB_FINISHED) {
          processJobCallOnFinish(event, jobProps, jobLogger);
        }
      } catch (final Throwable e) {
        jobLogger.error(
            "Encountered error while handling job callback event", e);
        logger.warn("Error during handleEvent for event " + event.getData().getStatus() + ", "
                + "execId: " + event.getData().getExecutionId());
        logger.warn(e.getMessage(), e);
      }
    } else {
      logger.warn("((( Got an unsupported runner: "
          + event.getRunner().getClass().getName() + " )))");
    }
  }

  private void processJobCallOnFinish(final Event event, final Props jobProps,
      final Logger jobLogger) {
    final EventData eventData = event.getData();

    if (!JobCallbackUtil.isThereJobCallbackProperty(jobProps,
        ON_COMPLETION_JOB_CALLBACK_STATUS)) {
      logger.info("No callback property for " + eventData.getStatus() + ", exec id: " +
          eventData.getExecutionId());
      return;
    }

    // don't want to waste time resolving properties if there are no
    // callback properties to parse
    // resolve props in best effort. Ignore the case where a referenced property is undefined.
    final Props props = PropsUtils.resolveProps(jobProps, true);

    final Map<String, String> contextInfo =
        JobCallbackUtil.buildJobContextInfoMap(event, this.azkabanHostName);

    JobCallbackStatusEnum jobCallBackStatusEnum = null;
    final Status jobStatus = eventData.getStatus();

    if (jobStatus == Status.SUCCEEDED) {
      jobCallBackStatusEnum = JobCallbackStatusEnum.SUCCESS;
    } else if (jobStatus == Status.FAILED
        || jobStatus == Status.FAILED_FINISHING || jobStatus == Status.KILLED) {
      jobCallBackStatusEnum = JobCallbackStatusEnum.FAILURE;
    } else {
      logger.info("!!!! WE ARE NOT SUPPORTING JOB CALLBACKS FOR STATUS: "
          + jobStatus);
      jobCallBackStatusEnum = null; // to be explicit
    }

    final String jobId = contextInfo.get(CONTEXT_JOB_TOKEN);

    if (jobCallBackStatusEnum != null) {
      final List<HttpRequestBase> jobCallbackHttpRequests =
          JobCallbackUtil.parseJobCallbackProperties(props,
              jobCallBackStatusEnum, contextInfo, maxNumCallBack, logger);

      if (!jobCallbackHttpRequests.isEmpty()) {
        final String msg =
            String.format("Making %d job callbacks for status: %s",
                jobCallbackHttpRequests.size(), jobCallBackStatusEnum.name());
        logger.info(msg);

        addDefaultHeaders(jobCallbackHttpRequests);

        JobCallbackRequestMaker.getInstance().makeHttpRequest(jobId, logger,
            jobCallbackHttpRequests);
      } else {
        logger.info("No job callbacks for status: " + jobCallBackStatusEnum);
      }
    }

    // for completed status
    final List<HttpRequestBase> httpRequestsForCompletedStatus =
        JobCallbackUtil.parseJobCallbackProperties(props, COMPLETED,
            contextInfo, maxNumCallBack, logger);

    // now make the call
    if (!httpRequestsForCompletedStatus.isEmpty()) {
      jobLogger.info("Making " + httpRequestsForCompletedStatus.size()
          + " job callbacks for status: " + COMPLETED);

      addDefaultHeaders(httpRequestsForCompletedStatus);
      JobCallbackRequestMaker.getInstance().makeHttpRequest(jobId, logger,
          httpRequestsForCompletedStatus);
    } else {
      logger.info("No job callbacks for status: " + COMPLETED);
    }
  }

  private void processJobCallOnStart(final Event event, final Props jobProps,
      final Logger jobLogger) {
    if (JobCallbackUtil.isThereJobCallbackProperty(jobProps,
        JobCallbackStatusEnum.STARTED)) {

      // don't want to waste time resolving properties if there are
      // callback properties to parse
      // resolve props in best effort. Ignore the case where a referenced property is undefined.
      final Props props = PropsUtils.resolveProps(jobProps, true);

      final Map<String, String> contextInfo =
          JobCallbackUtil.buildJobContextInfoMap(event, this.azkabanHostName);

      final List<HttpRequestBase> jobCallbackHttpRequests =
          JobCallbackUtil.parseJobCallbackProperties(props, STARTED,
              contextInfo, maxNumCallBack, logger);

      final String jobId = contextInfo.get(CONTEXT_JOB_TOKEN);
      final String msg =
          String.format("Making %d job callbacks for job %s for jobStatus: %s",
              jobCallbackHttpRequests.size(), jobId, STARTED.name());

      jobLogger.info(msg);

      addDefaultHeaders(jobCallbackHttpRequests);

      JobCallbackRequestMaker.getInstance().makeHttpRequest(jobId,
          logger, jobCallbackHttpRequests);
    }
  }

  private String getAzkabanHostName(final Props props) {
    final String baseURL = props.get(AZKABAN_WEBSERVER_URL);
    try {
      // Refer to the web server configuration in AzkabanServer.
      String hostName =
          props.getString(JETTY_HOSTNAME, "localhost") + ":" + (props.getBoolean(JETTY_USE_SSL
              , true) ? props.getInt(JETTY_SSL_PORT, DEFAULT_SSL_PORT_NUMBER) :
              props.getInt(JETTY_PORT, DEFAULT_PORT_NUMBER));
      if (baseURL != null) {
        final URL url = new URL(baseURL);
        hostName = url.getHost() + ":" + url.getPort();
      }
      return hostName;
    } catch (final Exception e) {
      throw new IllegalStateException(
          "Encountered while getting azkaban host name", e);
    }
  }

  private void addDefaultHeaders(final List<HttpRequestBase> httpRequests) {
    if (httpRequests == null) {
      return;
    }

    final SimpleDateFormat format =
        new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
    format.setTimeZone(TimeZone.getTimeZone("GMT"));

    for (final HttpRequestBase httpRequest : httpRequests) {
      httpRequest.addHeader(new BasicHeader("Date", this.gmtDateFormatter
          .format(new Date())));
    }

  }
}
