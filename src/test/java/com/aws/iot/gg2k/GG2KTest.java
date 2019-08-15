/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.gg2k;

import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;

public class GG2KTest {
    boolean seenDocker, seenShell;
    int seenTickTock = 4;
    long lastTickTock = 0;
    @Test
    public void testSomeMethod() {
        try {
            CountDownLatch OK = new CountDownLatch(3);
            String tdir = System.getProperty("user.home") + "/gg2ktest";
            System.out.println("tdir = " + tdir);
            GG2K gg = new GG2K();
            gg.setLogWatcher(e -> {
                if (e.args.length >= 3) {
                    String a0 = String.valueOf(e.args[0]);
                    String a1 = String.valueOf(e.args[2]);
                    if ("stdout".equals(a0)) {
//                        System.out.println("STDOUT: "+a1);
                        if (a1.contains("RUNNING") && !seenShell) {
                            OK.countDown();
//                            System.out.println("seenShell!");
                            seenShell = true;
                        } else if (a1.contains("docs.docker.com/") && !seenDocker) {
                            OK.countDown();
//                            System.out.println("seenDocker!");
                            seenDocker = true;
                        } else if (a1.contains("tick-tock")) {
                            long now = System.currentTimeMillis();
                            if (lastTickTock > 0) {
                                long delta = now - lastTickTock;
//                                System.out.println("lastTickTock test "+seenTickTock+ ": "+ delta + "  " + (delta - 2000));
                            }
                            lastTickTock = now;
                            if (--seenTickTock == 0) {
                                OK.countDown();
//                                System.out.println("seenTickTock!");
                            }
                        }
                    }
                }
            });
            gg.parseArgs("-r", tdir,
                    "-log", "stdout",
                    "-i", GG2K.class.getResource("config.yaml").toString()
            );
            gg.launch();
//            System.out.println("Done");
            boolean ok = OK.await(100, TimeUnit.SECONDS);
            gg.dump();
            gg.shutdown();
            assertTrue("docker hello world", seenDocker);
            assertTrue("bash hello world", seenShell);
            assertTrue("basic periodic test", seenTickTock <= 0);
            assertTrue("Test config didn't boot", ok);
//            System.out.println("Running correctly");
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }

}
