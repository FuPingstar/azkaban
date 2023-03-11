package priv.jobexecutor.process;


import azkaban.jobExecutor.utils.process.AzkabanProcess;
import azkaban.jobExecutor.utils.process.AzkabanProcessBuilder;
import org.junit.Test;

import java.io.IOException;

public class ProcessTest {

    @Test
    public void testUnixCommandExec() throws IOException {
        AzkabanProcessBuilder builder = new AzkabanProcessBuilder("pwd");
        AzkabanProcess process = builder.build();
        process.run();

    }
}
