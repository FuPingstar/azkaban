package priv;

import azkaban.db.DatabaseOperator;
import azkaban.executor.*;
import org.junit.*;

import java.sql.SQLException;
import java.util.List;

import static org.mockito.Mockito.mock;

public class ExecutionFlowTest {

    private static DatabaseOperator dbOperator;
    private ExecutionFlowDao executionFlowDao;

    @BeforeClass
    public static void setUp() throws Exception {
        dbOperator = Utils.initTestDB();
    }

//    @AfterClass
    public static void destroyDB() throws Exception {
        try {
            dbOperator.update("DROP ALL OBJECTS");
            dbOperator.update("SHUTDOWN");
        } catch (final SQLException e) {
            e.printStackTrace();
        }
    }

    @Before
    public void setup() {
        MysqlNamedLock mysqlNamedLock = mock(MysqlNamedLock.class);
        this.executionFlowDao = new ExecutionFlowDao(dbOperator, mysqlNamedLock);
    }

//    @After
    public void clearDB() {
        try {
            dbOperator.update("DELETE FROM execution_flows");
            dbOperator.update("DELETE FROM executors");
            dbOperator.update("DELETE FROM projects");
        } catch (final SQLException e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testFetchFlowHistory() {
        try {
            List<ExecutableFlow> flows = executionFlowDao.fetchFlowHistory(2, "flows", 0, 10);
        } catch (ExecutorManagerException e) {
            throw new RuntimeException(e);
        }
    }
}
