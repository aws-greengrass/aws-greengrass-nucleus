/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import java.util.concurrent.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

public class KernelTest {
//    boolean seenDocker, seenShell;
//    int seenTickTock = 4;
//    long lastTickTock = 0;
    @Test
    public void testSomeMethod() {
        try {
            String tdir = System.getProperty("user.home") + "/kernelTest";
            Kernel kernel = new Kernel();
            kernel.setLogWatcher(logline -> {
                if (logline.args.length >= 2) {
                    String a1 = String.valueOf(logline.args[1]);
                        boolean allDone = true;
                        for (Expected pattern : expectations) {
                            if (a1.contains(pattern.pattern))
                                if (++pattern.seen == 1) {
                                    System.out.println("KernelTest: Just saw " + pattern.message);
                                    OK[pattern.group].countDown();
                                    System.out.println("\tOK["+pattern.group+"]="+OK[pattern.group].getCount());
                                }
                            if (pattern.seen <= 0) allDone = false;
                        }
                }
            });
            kernel.parseArgs("-r", tdir,
                    "-log", "stdout",
                    "-i", Kernel.class.getResource("config.yaml").toString()
            );
            kernel.launch();
//            System.out.println("Done");
            boolean ok = OK[0].await(200, TimeUnit.SECONDS);
            testGroup(0);
            kernel.context.getLog().note("First phase passed, now for the harder stuff");
            kernel.find("main","run").setValue("while true; do\n" +
"        date; sleep 5; echo NEWMAIN\n" +
"        done");
//            kernel.writeConfig(new OutputStreamWriter(System.out));
            testGroup(1);
            kernel.context.getLog().note("Now merging delta.yaml");
            kernel.context.get(UpdateSystemSafelyService.class).addUpdateAction("test", 
              ()->kernel.readMerge(Kernel.class.getResource("delta.yaml"), false));
            testGroup(2);
            kernel.shutdown();
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }
    private void testGroup(int g) {
        try {
            OK[g].await(100,TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            ex.printStackTrace(System.out);
        }
        for (Expected pattern : expectations)
            if (pattern.seen <= 0 && pattern.group==g)
                fail("Didnt see: " + pattern.message);
    }

    private static class Expected {
        final String pattern;
        final String message;
        final int group;
        int seen = 0;
        Expected(int g, String p, String m, int n) {
            group = g;
            pattern = p;
            message = m;
            seen = 1 - n;
            gc[g]++;
        }
        Expected(int group, String p, String m) {
            this(group, p, m, 1);
        }
    }
    static final int[] gc = new int[10];
    static final CountDownLatch[] OK = new CountDownLatch[10];
    private static final Expected[] expectations = {
        new Expected(0,"RUNNING", "Main service"),
        //new Expected("docs.docker.com/", "docker hello world"),
        new Expected(0,"tick-tock", "periodic", 3),
        new Expected(0,"ANSWER=42", "global setenv"),
        new Expected(0,"EVERGREEN_UID=", "generated unique token"),
        new Expected(0,"mqtt.moquette.run", "moquette mqtt server"),
        new Expected(0,"JUSTME=fancy a spot of tea?", "local setenv in main service"),
        new Expected(1,"NEWMAIN","Assignment to 'run' script'"),
        new Expected(2,"JUSTME=fancy a spot of coffee?", "merge yaml"),
    };
    static {
        for(int i = gc.length; --i>=0; )
            OK[i] = new CountDownLatch(gc[i]);
    }
}
