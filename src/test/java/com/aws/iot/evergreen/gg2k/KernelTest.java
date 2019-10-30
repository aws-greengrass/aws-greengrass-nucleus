/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.gg2k;

import java.util.concurrent.*;

import com.aws.iot.evergreen.Kernel;
import org.junit.*;
import static org.junit.Assert.*;

public class KernelTest {
//    boolean seenDocker, seenShell;
//    int seenTickTock = 4;
//    long lastTickTock = 0;
    @Test
    public void testSomeMethod() {
        try {
            CountDownLatch OK = new CountDownLatch(1);
            String tdir = System.getProperty("user.home") + "/kernelTest";
//            System.out.println("tdir = " + tdir);
            Kernel kernel = new Kernel();
            kernel.setLogWatcher(logline -> {
                if (logline.args.length >= 2) {
//                    String a0 = String.valueOf(logline.args[0]);
                    String a1 = String.valueOf(logline.args[1]);
//                    if (a0.endsWith(".run")) {
                        boolean allDone = true;
                        for (Expected pattern : expectations) {
                            if (a1.contains(pattern.pattern))
                                if (++pattern.seen == 1)
                                    System.out.println("Just saw " + pattern.message);
                            if (pattern.seen <= 0) allDone = false;
                        }
                        if (allDone) OK.countDown();
//                    }
                }
            });
            kernel.parseArgs("-r", tdir,
                    "-log", "stdout",
                    "-i", Kernel.class.getResource("config.yaml").toString()
            );
            kernel.launch();
//            System.out.println("Done");
            boolean ok = OK.await(200, TimeUnit.SECONDS);
            kernel.dump();
            kernel.shutdown();
            for (Expected pattern : expectations)
                if (pattern.seen <= 0)
                    fail("Didnt see: " + pattern.message);
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }

    private static class Expected {
        final String pattern;
        final String message;
        int seen = 0;
        Expected(String p, String m, int n) {
            pattern = p;
            message = m;
            seen = 1 - n;
        }
        Expected(String p, String m) {
            this(p, m, 1);
        }
    }
    private final Expected[] expectations = {
        new Expected("RUNNING", "Main service"),
        //new Expected("docs.docker.com/", "docker hello world"),
        new Expected("tick-tock", "periodic", 3),
        new Expected("ANSWER=42", "global setenv"),
        new Expected("EVERGREEN_UID=", "generated unique token"),
        new Expected("mqtt.moquette.run", "moquette mqtt server"),
        new Expected("JUSTME=fancy a spot of tea?", "local setenv in main service"),};

}
