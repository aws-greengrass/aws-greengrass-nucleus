package com.aws.iot.evergreen.ipc;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.ipc.config.KernelIPCClientConfig;

import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class IPCTest {

    public static int port;
    public static String address;
    public static Kernel kernel;
    @BeforeAll
    public static void setup() throws Exception {
        // starting daemon
        CountDownLatch OK = new CountDownLatch(1);
        String tdir = System.getProperty("user.home") + "/gg2ktest";
        System.out.println("tdir = " + tdir);
        kernel = new Kernel();

        kernel.setLogWatcher(logline -> {
            if (logline.args.length == 1 && logline.args[0].equals("Run called for IPC service") ) {
                OK.countDown();
            }
        });

        kernel.parseArgs("-r", tdir,
                "-log", "stdout",
                "-i", IPCTest.class.getResource("ipc.yaml").toString()
        );
        kernel.launch();
        OK.await(10, TimeUnit.SECONDS);
        IPCService ipc = (IPCService) kernel.context.getvIfExists("IPCService").get();
        Topic portTopic = (Topic) ipc.config.getChild("port");
        port = Integer.valueOf((String)portTopic.getOnce());
        address = "127.0.0.1";
    }


    @AfterAll
    public static void teardown() {
        kernel.shutdown();
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
//        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress(address).port(port).token("duplicateClientId").build();
//        IPCClient client1 = new IPCClientImpl(config);
//        IPCClient client2 = new IPCClientImpl(config);
//
//        client1.connect();
//        client2.connect();
    }
}
