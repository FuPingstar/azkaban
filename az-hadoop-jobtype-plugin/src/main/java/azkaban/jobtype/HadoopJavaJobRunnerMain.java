/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package azkaban.jobtype;

import static azkaban.security.commons.SecurityUtils.MAPREDUCE_JOB_CREDENTIALS_BINARY;
import static org.apache.hadoop.security.UserGroupInformation.HADOOP_TOKEN_FILE_LOCATION;

import azkaban.Constants;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Layout;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import azkaban.jobExecutor.ProcessJob;
import azkaban.security.commons.HadoopSecurityManager;
import azkaban.utils.JSONUtils;
import azkaban.utils.Props;


public class HadoopJavaJobRunnerMain {

  public static final String JOB_CLASS = "job.class";
  public static final String DEFAULT_RUN_METHOD = "run";
  public static final String DEFAULT_CANCEL_METHOD = "cancel";

  // This is the Job interface method to get the properties generated by the
  // job.
  public static final String GET_GENERATED_PROPERTIES_METHOD =
      "getJobGeneratedProperties";

  public static final String CANCEL_METHOD_PARAM = "method.cancel";
  public static final String RUN_METHOD_PARAM = "method.run";
  public static final String[] PROPS_CLASSES = new String[]{
      "azkaban.utils.Props", "azkaban.common.utils.Props"};

  private static final Layout DEFAULT_LAYOUT = new PatternLayout("%p %m\n");

  public final Logger _logger;

  public String _cancelMethod;
  public String _jobName;
  public Object _javaObject;
  private boolean _isFinished = false;

  private static boolean securityEnabled;

  public static void main(String[] args) throws Exception {
    @SuppressWarnings("unused")
    HadoopJavaJobRunnerMain wrapper = new HadoopJavaJobRunnerMain();
  }

  public HadoopJavaJobRunnerMain() throws Exception {
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override
      public void run() {
        cancelJob();
      }
    });

    // Separate try catch for logger
    try {
      _logger = Logger.getRootLogger();
    } catch (Exception e) {
      throw e;
    }

    try {
      _jobName = System.getenv(ProcessJob.JOB_NAME_ENV);
      String jobPropsFile = System.getenv(ProcessJob.JOB_PROP_ENV);
      String privatePropsFile = System.getenv(ProcessJob.JOBTYPE_PRIVATE_PROP_ENV);


      Properties jobProps = new Properties(), privateProps = new Properties();

      BufferedReader br = new BufferedReader(new InputStreamReader(
          new FileInputStream(jobPropsFile), StandardCharsets.UTF_8));
      jobProps.load(br);

      HadoopConfigurationInjector.injectResources(new Props(null, jobProps));
      if (privatePropsFile != null) {
        br = new BufferedReader(new InputStreamReader(
            new FileInputStream(privatePropsFile), StandardCharsets.UTF_8));
        privateProps.load(br);
      }

      final Configuration conf = new Configuration();

      UserGroupInformation.setConfiguration(conf);
      securityEnabled = UserGroupInformation.isSecurityEnabled();

      _logger.info("Running job " + _jobName);
      String className = jobProps.getProperty(JOB_CLASS);
      if (className == null) {
        throw new Exception("Class name is not set.");
      }
      _logger.info("Class name " + className);

      UserGroupInformation loginUser = null;
      UserGroupInformation proxyUser = null;

      if (shouldProxy(jobProps)) {
        String userToProxy = jobProps.getProperty("user.to.proxy");
        if (securityEnabled) {
          String filelocation = System.getenv(HADOOP_TOKEN_FILE_LOCATION);
          _logger.info("Found token file " + filelocation);
          _logger.info("Security enabled is "
              + UserGroupInformation.isSecurityEnabled());

          _logger.info("Setting mapreduce.job.credentials.binary to "
              + filelocation);
          System.setProperty("mapreduce.job.credentials.binary", filelocation);

          _logger.info("Proxying enabled.");

          loginUser = UserGroupInformation.getLoginUser();

          _logger.info("Current logged in user is " + loginUser.getUserName());

          proxyUser =
              UserGroupInformation.createProxyUser(userToProxy, loginUser);
          for (Token<?> token : loginUser.getTokens()) {
            proxyUser.addToken(token);
          }
          proxyUser.addCredentials(loginUser.getCredentials());
        } else {
          proxyUser = UserGroupInformation.createRemoteUser(userToProxy);

          if (jobProps.getProperty(Constants.JobProperties.ENABLE_OAUTH, "false").equals("true")) {
            proxyUser.addCredentials(UserGroupInformation.getLoginUser().getCredentials());
          }
        }
        _logger.info("Proxied as user " + userToProxy);
        // Create the object using proxy
        _javaObject =
            getObjectAsProxyUser(jobProps, privateProps, _logger, _jobName, className, proxyUser);
      } else {
        // Create the object
        _javaObject = getObject(_jobName, className, jobProps, privateProps, _logger);
      }

      if (_javaObject == null) {
        _logger.info("Could not create java object to run job: " + className);
        throw new Exception("Could not create running object");
      }
      _logger.info("Got object " + _javaObject.toString());

      _cancelMethod =
          jobProps.getProperty(CANCEL_METHOD_PARAM, DEFAULT_CANCEL_METHOD);

      final String runMethod =
          jobProps.getProperty(RUN_METHOD_PARAM, DEFAULT_RUN_METHOD);

      if (shouldProxy(jobProps)) {
        _logger.info("Proxying enabled.");
        runMethodAsUser(_javaObject, runMethod, proxyUser);
      } else {
        _logger.info("Proxy check failed, not proxying run.");
        runMethod(_javaObject, runMethod);
      }

      _isFinished = true;

      // Get the generated properties and store them to disk, to be read
      // by ProcessJob.
      try {
        final Method generatedPropertiesMethod =
            _javaObject.getClass().getMethod(GET_GENERATED_PROPERTIES_METHOD,
                new Class<?>[]{});
        Object outputGendProps =
            generatedPropertiesMethod.invoke(_javaObject, new Object[]{});

        if (outputGendProps != null) {
          final Method toPropertiesMethod =
              outputGendProps.getClass().getMethod("toProperties",
                  new Class<?>[]{});
          Properties properties =
              (Properties) toPropertiesMethod.invoke(outputGendProps,
                  new Object[]{});

          Props outputProps = new Props(null, properties);
          outputGeneratedProperties(outputProps);
        } else {
          _logger.info(GET_GENERATED_PROPERTIES_METHOD
              + " method returned null.  No properties to pass along");
        }
      } catch (NoSuchMethodException e) {
        _logger.info(String.format(
            "Apparently there isn't a method[%s] on object[%s], using "
                + "empty Props object instead.",
            GET_GENERATED_PROPERTIES_METHOD, _javaObject));
        outputGeneratedProperties(new Props());
      }
    } catch (Exception e) {
      _isFinished = true;
      _logger.error("Exception propagated to Azkaban from job code");
      throw e;
    }
  }

  private void runMethodAsUser(final Object obj, final String runMethod,
      final UserGroupInformation ugi)
      throws IOException, InterruptedException, UndeclaredThrowableException {
    ugi.doAs((PrivilegedExceptionAction<Void>) () -> {

      Configuration conf = new Configuration();
      if (System.getenv(HADOOP_TOKEN_FILE_LOCATION) != null) {
        conf.set(MAPREDUCE_JOB_CREDENTIALS_BINARY,
            System.getenv(HADOOP_TOKEN_FILE_LOCATION));
      }

      runMethod(obj, runMethod);
      return null;
    });
  }

  private void runMethod(Object obj, String runMethod)
      throws IllegalAccessException, InvocationTargetException,
      NoSuchMethodException {
    final Method method = obj.getClass().getMethod(runMethod, new Class<?>[]{});
    _logger.info("Beginning execution of external code: " + runMethod);
    method.invoke(obj);
    _logger.info("Completed execution of external code: " + runMethod);
  }

  private void outputGeneratedProperties(Props outputProperties) {
    _logger.info("Outputting generated properties to "
        + ProcessJob.JOB_OUTPUT_PROP_FILE);

    if (outputProperties == null) {
      _logger.info("  no gend props");
      return;
    }
    for (String key : outputProperties.getKeySet()) {
      _logger
          .info("  gend prop " + key + " value:" + outputProperties.get(key));
    }

    String outputFileStr = System.getenv(ProcessJob.JOB_OUTPUT_PROP_FILE);
    if (outputFileStr == null) {
      return;
    }

    Map<String, String> properties = new LinkedHashMap<String, String>();
    for (String key : outputProperties.getKeySet()) {
      properties.put(key, outputProperties.get(key));
    }

    Writer writer = null;
    try {
//      writer = new BufferedWriter(new FileWriter(outputFileStr));
      writer = Files.newBufferedWriter(Paths.get(outputFileStr), Charset.defaultCharset());

      JSONUtils.writePropsNoJarDependency(properties, writer);
    } catch (Exception e) {

    } finally {
      if (writer != null) {
        try {
          writer.close();
        } catch (IOException e) {
        }
      }
    }
  }

  public void cancelJob() {
    if (_isFinished) {
      return;
    }
    _logger.info("Attempting to call cancel on this job");
    if (_javaObject != null) {
      Method method = null;

      try {
        method = _javaObject.getClass().getMethod(_cancelMethod);
      } catch (SecurityException e) {
      } catch (NoSuchMethodException e) {
      }

      if (method != null) {
        try {
          method.invoke(_javaObject);
        } catch (Exception e) {
          _logger.error("Cancel method failed! ", e);
        }
      } else {
        throw new RuntimeException("Job " + _jobName
            + " does not have cancel method " + _cancelMethod);
      }
    }
  }

  private static Object getObjectAsProxyUser(final Properties jobProp,
      final Properties privateProp, final Logger logger, final String jobName,
      final String className, final UserGroupInformation ugi)
      throws Exception {
    return ugi.doAs(
        (PrivilegedExceptionAction<Object>) () ->
            getObject(jobName, className, jobProp, privateProp, logger));
  }

  private static Object getObject(final String jobName,
      final String className, final Properties jobProperties,
      final Properties privateProperties, Logger logger) throws Exception {

    Class<?> runningClass =
        HadoopJavaJobRunnerMain.class.getClassLoader().loadClass(className);

    if (runningClass == null) {
      throw new Exception("Class " + className
          + " was not found. Cannot run job.");
    }

    Class<?> propsClass = null;
    for (String propClassName : PROPS_CLASSES) {
      try {
        propsClass =
            HadoopJavaJobRunnerMain.class.getClassLoader().loadClass(
                propClassName);
      } catch (ClassNotFoundException e) {
      }

      if (propsClass != null
          && ((getConstructor(runningClass, String.class, propsClass) != null)
          || (getConstructor(runningClass, String.class, propsClass, propsClass) != null))) {
        // is this the props class
        break;
      }
      propsClass = null;
    }

    Object obj = null;
    if (propsClass != null) {
      // Create jobProps class
      Constructor<?> jobPropsCon =
          getConstructor(propsClass, propsClass, Properties[].class);
      Object jobProps =
          jobPropsCon.newInstance(null, new Properties[]{jobProperties});

      Constructor<?> con = getConstructor(runningClass, String.class, propsClass);
      if (con != null) {
        logger.info("Constructor found " + con.toGenericString());
        obj = con.newInstance(jobName, jobProps);
      } else {
        // Handle cases where jobtype has constructor of format
        // (java.lang.String,azkaban.utils.Props,azkaban.utils.Props)
        con = getConstructor(runningClass, String.class, propsClass, propsClass);
        if (con != null) {
          // Create privateProps class
          Constructor<?> privatePropsCon =
              getConstructor(propsClass, propsClass, Properties[].class);
          Object privateProps =
              privatePropsCon.newInstance(null, new Properties[]{privateProperties});
          logger.info("Constructor found " + con.toGenericString());
          obj = con.newInstance(jobName, privateProps, jobProps);
        }
      }
    } else if (getConstructor(runningClass, String.class, Properties.class) != null) {

      Constructor<?> con =
          getConstructor(runningClass, String.class, Properties.class);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance(jobName, jobProperties);
    } else if (getConstructor(runningClass, String.class, Map.class) != null) {
      Constructor<?> con =
          getConstructor(runningClass, String.class, Map.class);
      logger.info("Constructor found " + con.toGenericString());

      HashMap<Object, Object> map = new HashMap<Object, Object>();
      for (Map.Entry<Object, Object> entry : jobProperties.entrySet()) {
        map.put(entry.getKey(), entry.getValue());
      }
      obj = con.newInstance(jobName, map);
    } else if (getConstructor(runningClass, String.class) != null) {
      Constructor<?> con = getConstructor(runningClass, String.class);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance(jobName);
    } else if (getConstructor(runningClass) != null) {
      Constructor<?> con = getConstructor(runningClass);
      logger.info("Constructor found " + con.toGenericString());
      obj = con.newInstance();
    } else {
      logger.error("Constructor not found. Listing available Constructors.");
      for (Constructor<?> c : runningClass.getConstructors()) {
        logger.info(c.toGenericString());
      }
    }
    return obj;
  }

  private static Constructor<?> getConstructor(Class<?> c, Class<?>... args) {
    try {
      return c.getConstructor(args);
    } catch (NoSuchMethodException e) {
      return null;
    }
  }

  public boolean shouldProxy(Properties props) {
    String shouldProxy =
        props.getProperty(HadoopSecurityManager.ENABLE_PROXYING);

    return shouldProxy != null && shouldProxy.equals("true");
  }

}
