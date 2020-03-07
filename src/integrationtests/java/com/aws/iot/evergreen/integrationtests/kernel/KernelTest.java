/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(PerformanceReporting.class)
class KernelTest {
    static final int[] gc = new int[10];
    static final CountDownLatch[] OK = new CountDownLatch[10];
    private static final Expected[] expectations = {new Expected(0, "\"stdout\":\"RUNNING\"", "Main service"),
            //new Expected("docs.docker.com/", "docker hello world"),
            new Expected(0, "\"stdout\":\"tick-tock\"", "periodic", 3),
            new Expected(0, "\"stdout\":\"ANSWER=42\"", "global setenv"),
            new Expected(0, "\"stdout\":\"EVERGREEN_UID=", "generated unique token"),
            new Expected(0, "\"stdout\":\"version: 0.12.1\"", "moquette mqtt server"),
            new Expected(0, "\"stdout\":\"JUSTME=fancy a spot of tea?\"", "local setenv in main service"),
            new Expected(1, "\"stdout\":\"NEWMAIN\"", "Assignment to 'run' script'"),
            new Expected(2, "\"stdout\":\"JUSTME=fancy a spot of coffee?\"", "merge yaml"),
            new Expected(2, "\"stdout\":\"I'm Frodo\"", "merge adding dependency")};
    static final String LogFileName = "test.log";

    static {
        for (int i = gc.length; --i >= 0; ) {
            OK[i] = new CountDownLatch(gc[i]);
        }
    }

    @TempDir
    Path tempRootDir;

    @BeforeAll
    static void setup() {
        System.setProperty("log.fmt", "JSON");
        System.setProperty("log.storeName", LogFileName);
        System.setProperty("log.store", "FILE");
        System.setProperty("log.level", "INFO");
        try {
            Files.deleteIfExists(Paths.get(LogFileName));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @AfterAll
    static void cleanup() {
        try {
            Files.deleteIfExists(Paths.get(LogFileName));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Test
    void testErrorRetry() throws InterruptedException {
        Kernel kernel = new Kernel();
        kernel.parseArgs("-r", tempRootDir.toString(), "-log", "stdout", "-i",
                getClass().getResource("config_broken.yaml").toString());

        LinkedList<ExpectedStateTransition> expectedStateTransitionList = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition("installErrorRetry", State.NEW, State.ERRORED),
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

            if (service.getName().equals(expected.serviceName) && was.equals(expected.was) && service.getState()
                    .equals(expected.current)) {
                System.out.println(String.format("Just saw state event for service %s: %s=> %s", expected.serviceName,
                        expected.was, expected.current));

                expectedStateTransitionList.pollFirst();
                if (expectedStateTransitionList.size() == 0) {
                    // all assersion done.
                    assertionLatch.countDown();
                }
            }

        });
        kernel.launch();
        boolean ok = assertionLatch.await(60, TimeUnit.SECONDS);

        kernel.shutdownNow();
        if (expectedStateTransitionList.size() != 0 || !ok) {
            for (ExpectedStateTransition e : expectedStateTransitionList) {
                System.err.println(
                        String.format("Fail to see state event for service %s: %s=> %s", e.serviceName, e.was,
                                e.current));
            }
            fail("Not seen all expected state transitions");
        }
    }

    @Test
    void testSomeMethod() throws Exception {
        Runnable runnable = () -> {
            File fileToWatch = new File(LogFileName);
            long lastKnownPosition = 0;

            while (!fileToWatch.exists()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            try {
                while (true) {
                    Thread.sleep(1000);
                    long fileLength = fileToWatch.length();

                    /**
                     * This case occur, when file is taken backup and new file
                     * created with the same name.
                     */
                    if (fileLength < lastKnownPosition) {
                        lastKnownPosition = 0;
                    }
                    if (fileLength > lastKnownPosition) {
                        RandomAccessFile randomAccessFile = new RandomAccessFile(fileToWatch, "r");
                        randomAccessFile.seek(lastKnownPosition);
                        String line = null;
                        while ((line = randomAccessFile.readLine()) != null) {
                            for (Expected pattern : expectations) {
                                if (line.contains(pattern.pattern)) {
                                    if (++pattern.seen == 1) {
                                        System.out.println("KernelTest: Just saw " + pattern.message);
                                        OK[pattern.group].countDown();
                                        System.out
                                                .println("\tOK[" + pattern.group + "]=" + OK[pattern.group].getCount());
                                    }
                                }
                            }
                        }
                        lastKnownPosition = randomAccessFile.getFilePointer();
                        randomAccessFile.close();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
        Thread thread = new Thread(runnable);
        thread.start();

        Kernel kernel = new Kernel();
        kernel.parseArgs("-r", tempRootDir.toString(), "-log", "stdout", "-i",
                KernelTest.class.getResource("config.yaml").toString());
        kernel.launch();
        boolean ok = OK[0].await(200, TimeUnit.SECONDS);
        assertTrue(ok);
        testGroup(0);
        System.out.println("First phase passed, now for the harder stuff");

        kernel.find("main", "run")
                .setValue("while true; do\n" + "        date; sleep 5; echo NEWMAIN\n" + "     " + "   done");
        //            kernel.writeConfig(new OutputStreamWriter(System.out));
        testGroup(1);

        System.out.println("Now merging delta.yaml");
        kernel.mergeInNewConfig("ID", System.currentTimeMillis(),
                (Map<Object, Object>) JSON.std.with(new YAMLFactory()).anyFrom(getClass().getResource("delta"
                        + ".yaml")))
                .get(60, TimeUnit.SECONDS);
        testGroup(2);
        kernel.shutdown();
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
