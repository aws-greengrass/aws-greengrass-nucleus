package com.aws.iot.ipc;


import com.aws.iot.config.Topic;
import com.aws.iot.evergreen.ipc.IPCClient;
import com.aws.iot.evergreen.ipc.IPCClientImpl;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;
import com.aws.iot.gg2k.GG2K;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

public class IPCTest {

    public static int port;
    public static String address;
    public static GG2K gg;
    @BeforeClass
    public static void setup() throws Exception {
        // starting daemon
        CountDownLatch OK = new CountDownLatch(1);
        String tdir = System.getProperty("user.home") + "/gg2ktest";
        System.out.println("tdir = " + tdir);
        gg = new GG2K();

        gg.setLogWatcher(logline -> {
            if (logline.args.length == 1 && logline.args[0].equals("Run called for IPC service") ) {
                OK.countDown();
            }
        });

        gg.parseArgs("-r", tdir,
                "-log", "stdout",
                "-i", IPCTest.class.getResource("ipc.yaml").toString()
        );
        gg.launch();
        OK.await(10, TimeUnit.SECONDS);
        IPCService ipc = (IPCService) gg.context.getvIfExists("IPCService").get();
        Topic portTopic = (Topic) ipc.config.getChild("port");
        port = Integer.valueOf((String)portTopic.getOnce());
        address = "127.0.0.1";
    }


    @AfterClass
    public static void teardown() {
        gg.shutdown();
    }

    @Test
    public void pingTest() throws Exception {
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port).token("pingTest").build();
        IPCClient client = new IPCClientImpl(config);
        client.connect();
        assertTrue(client.ping());
        assertTrue(client.ping());
        assertTrue(client.ping());
        client.disconnect();
    }

    @Test
    public void duplicateClientId() throws Exception {
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port).token("duplicateClientId").build();
        IPCClient client1 = new IPCClientImpl(config);
        IPCClient client2 = new IPCClientImpl(config);

        client1.connect();
        client2.connect();
    }
}
