package com.aws.iot.ipc;

import com.aws.iot.gg2k.GG2KTest;
import com.aws.iot.gg2k.client.KernelIPCClient;
import com.aws.iot.gg2k.client.KernelIPCClientImpl;
import com.aws.iot.gg2k.client.config.KernelIPCClientConfig;
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
            if (logline.args.length == 1 && logline.args[0].equals("IPC Server listening")) {
                OK.countDown();
            }
        });

        gg.parseArgs("-r", tdir,
                "-log", "stdout",
                "-i", getClass().getResource("ipc.yaml").toString()
        );
        gg.launch();
        OK.await(30, TimeUnit.SECONDS);
        KernelIPCClientConfig config = KernelIPCClientConfig.builder().hostAddress("127.0.0.1").port(20020).build();
        KernelIPCClient client = new KernelIPCClientImpl(config);
        assertTrue(client.ping());
    }
}
