package com.aws.iot.ipc;


import com.aws.iot.config.Topic;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class IPCTest {

    @Test
    public void pingTest() throws Exception {
        CountDownLatch OK = new CountDownLatch(1);
        String tdir = System.getProperty("user.home") + "/gg2ktest";
        System.out.println("tdir = " + tdir);
        com.aws.iot.gg2k.GG2K gg = new com.aws.iot.gg2k.GG2K();

        gg.setLogWatcher(logline -> {
            if (logline.args.length == 1 && logline.args[0].equals("Run called for IPC service") ) {
                OK.countDown();
            }
        });

        gg.parseArgs("-r", tdir,
                "-log", "stdout",
                "-i", getClass().getResource("ipc.yaml").toString()
        );
        gg.launch();
        OK.await(10, TimeUnit.SECONDS);

        IPCService ipc = (IPCService) gg.context.getvIfExists("IPCService").get();
        Topic port = (Topic) ipc.config.getChild("port");

        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress("127.0.0.1").port( Integer.valueOf((String)port.getOnce())).build();
        IPCClient client = new IPCClientImpl(config);
        client.connect();
        assertTrue(client.ping());
        assertTrue(client.ping());
        assertTrue(client.ping());
        client.disconnect();

        gg.shutdown();
    }
}
