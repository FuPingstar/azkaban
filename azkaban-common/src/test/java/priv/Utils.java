package priv;


import azkaban.db.AzkabanDataSource;
import azkaban.db.DBMetrics;
import azkaban.db.DatabaseOperator;
import azkaban.db.MySQLDataSource;
import azkaban.metrics.MetricsManager;
import azkaban.utils.Props;
import com.codahale.metrics.MetricRegistry;
import org.apache.commons.dbutils.QueryRunner;
import org.junit.Test;

public class Utils {

    @Test
    public static DatabaseOperator initTestDB() throws Exception {

        Props props = Props.of(
                "mysql.port", "3306", "mysql.host", "127.0.0.1", "mysql.database",
                "azkaban", "mysql.user", "root", "mysql.password", "mac_mysql", "mysql.numconnections", "10");
        final MetricRegistry metricRegistry = new MetricRegistry();
        final DBMetrics metrics = new DBMetrics(new MetricsManager(metricRegistry));
        final AzkabanDataSource dataSource = new MySQLDataSource(props, metrics);
        return new DatabaseOperator(new QueryRunner(dataSource));
    }
}
