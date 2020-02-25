/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.dependency.State;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Tag("Integration")
public class KernelTest {
    static final int[] gc = new int[10];
    static final CountDownLatch[] OK = new CountDownLatch[10];
    private static final Expected[] expectations = {new Expected(0, "RUNNING", "Main service"),
            //new Expected("docs.docker.com/", "docker hello world"),
            new Expected(0, "tick-tock", "periodic", 3),
            new Expected(0, "ANSWER=42", "global setenv"),
            new Expected(0, "EVERGREEN_UID=", "generated unique token"), new Expected(0, "mqtt.moquette.run",
            "moquette mqtt server"), new Expected(0, "JUSTME=fancy a spot of tea?", "local setenv in main service"),
            new Expected(1, "NEWMAIN", "Assignment to 'run' script'"), new Expected(2, "JUSTME=fancy a spot of " +
            "coffee?", "merge yaml"),};

    static {
        for (int i = gc.length; --i >= 0; ) {
            OK[i] = new CountDownLatch(gc[i]);
        }
    }

    //    boolean seenDocker, seenShell
    //    int seenTickTock = 4;
    //    long lastTickTock = 0;
    @Test
    public void testErrorRetry() throws InterruptedException {
        String tdir = System.getProperty("user.home") + "/kernelTest";
        Kernel kernel = new Kernel();
        kernel.parseArgs("-r", tdir, "-log", "stdout", "-i", Kernel.class.getResource("config_broken.yaml").toString());

        LinkedList<ExpectedStateTransition> expectedStateTransitionList =
                new LinkedList<>(Arrays.asList(
                        new ExpectedStateTransition("installErrorRetry", State.NEW, State.ERRORED),
                        new ExpectedStateTransition("installErrorRetry", State.ERRORED, State.NEW),
                        new ExpectedStateTransition("installErrorRetry", State.NEW, State.INSTALLED),

                        // main service doesn't start until dependency ready
                        new ExpectedStateTransition("runErrorRetry", State.INSTALLED, State.RUNNING),
                        new ExpectedStateTransition("main", State.INSTALLED, State.RUNNING),

                        // runErrorRetry restart on error
                        new ExpectedStateTransition("runErrorRetry", State.RUNNING, State.ERRORED),
                        new ExpectedStateTransition("runErrorRetry", State.ERRORED, State.STOPPING),
                        new ExpectedStateTransition("runErrorRetry", State.STOPPING, State.FINISHED),
                        new ExpectedStateTransition("runErrorRetry", State.FINISHED, State.INSTALLED),

                        // main service restart on dependency error
                        new ExpectedStateTransition("runErrorRetry", State.RUNNING, State.ERRORED),
                        new ExpectedStateTransition("main", State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition("runErrorRetry", State.INSTALLED, State.RUNNING),
                        new ExpectedStateTransition("main", State.INSTALLED, State.RUNNING)));

        CountDownLatch assertionLatch = new CountDownLatch(1);

        kernel.context.addGlobalStateChangeListener((EvergreenService service, State was) -> {
            if (expectedStateTransitionList.size() == 0) {
                return;
            }

            ExpectedStateTransition expected = expectedStateTransitionList.peek();

            if (service.getName().equals(expected.serviceName) && was.equals(expected.was) && service.getState().equals(expected.current)) {
                System.out.println(String.format("Just saw state event for service %s: %s=> %s", expected.serviceName
                        , expected.was, expected.current));

                expectedStateTransitionList.pollFirst();
                if (expectedStateTransitionList.size() == 0) {
                    // all assersion done.
                    assertionLatch.countDown();
                }
            }

        });
        kernel.launch();
        boolean ok = assertionLatch.await(60, TimeUnit.SECONDS);

        kernel.shutdown();
        if (expectedStateTransitionList.size() != 0 || !ok) {
            for (ExpectedStateTransition e : expectedStateTransitionList) {
                System.err.println(String.format("Fail to see state event for service %s: %s=> %s", e.serviceName,
                        e.was, e.current));
            }
            fail("Not seen all expected state transitions");
        }
    }

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
                        if (a1.contains(pattern.pattern)) {
                            if (++pattern.seen == 1) {
                                System.out.println("KernelTest: Just saw " + pattern.message);
                                OK[pattern.group].countDown();
                                System.out.println("\tOK[" + pattern.group + "]=" + OK[pattern.group].getCount());
                            }
                        }
                        if (pattern.seen <= 0) {
                            allDone = false;
                        }
                    }
                }
            });
            kernel.parseArgs("-r", tdir, "-log", "stdout", "-i", Kernel.class.getResource("config.yaml").toString());
            kernel.launch();
            boolean ok = OK[0].await(200, TimeUnit.SECONDS);
            assertTrue(ok);
            testGroup(0);
            System.out.println("First phase passed, now for the harder stuff");
            kernel.find("main", "run").setValue("while true; do\n" + "        date; sleep 5; echo NEWMAIN\n" + "     " +
                    "   done");
            //            kernel.writeConfig(new OutputStreamWriter(System.out));
            testGroup(1);
            kernel.context.getLog().note("Now merging delta.yaml");
            kernel.context.get(UpdateSystemSafelyService.class).addUpdateAction("test",
                    () -> kernel.readMerge(Kernel.class.getResource("delta.yaml"), false));
            testGroup(2);
            kernel.shutdown();
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }

    private void testGroup(int g) {
        try {
            OK[g].await(100, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            ex.printStackTrace(System.out);
        }
        for (Expected pattern : expectations) {
            if (pattern.seen <= 0 && pattern.group == g) {
                fail("Didnt see: " + pattern.message);
            }
        }
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

    private class ExpectedStateTransition {
        final String serviceName;
        final State was;
        final State current;

        public ExpectedStateTransition(String name, State was, State current) {
            this.serviceName = name;
            this.was = was;
            this.current = current;
        }
    }
}
