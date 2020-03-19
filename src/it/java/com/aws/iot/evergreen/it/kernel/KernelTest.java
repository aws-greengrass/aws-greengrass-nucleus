/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.it.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.it.AbstractBaseITCase;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.extensions.PerformanceReporting;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(PerformanceReporting.class)
class KernelTest extends AbstractBaseITCase {
    private static final String LOG_FILE_NAME = "KernelTest.log";
    private static final String LOG_FILE_PATH_NAME = tempRootDir.resolve(LOG_FILE_NAME).toAbsolutePath().toString();


    private static final ExpectedMessage[] EXPECTED_MESSAGES = {new ExpectedMessage(0, "MAIN IS RUNNING", "Main service"),
            //new ExpectedMessage("docs.docker.com/", "docker hello world"),
            new ExpectedMessage(0, "tick-tock", "periodic", 3), new ExpectedMessage(0, "ANSWER=42", "global setenv"),
            new ExpectedMessage(0, "EVERGREEN_UID=", "generated unique token"),
            new ExpectedMessage(0, "version: 0.12.1", "moquette mqtt server"),
            new ExpectedMessage(0, "JUSTME=fancy a spot of tea?", "local setenv in main service"),
            new ExpectedMessage(1, "NEWMAIN", "Assignment to 'run' script'"),
            new ExpectedMessage(2, "JUSTME=fancy a spot of coffee?", "merge yaml"),
            new ExpectedMessage(2, "I'm Frodo", "merge adding dependency")};

    private static final CountDownLatch[] COUNT_DOWN_LATCHES =
            {new CountDownLatch(6), new CountDownLatch(1), new CountDownLatch(2)};

    @BeforeAll
    static void beforeAll() {
        // TODO Refactor with Log Listener
        // override log store to a file for legacy kernel test to verify logs
        System.setProperty("log.store", "FILE");
        System.setProperty("log.level", "INFO");
        System.setProperty("log.storeName", LOG_FILE_PATH_NAME);
        System.out.println("Storing log to: " + LOG_FILE_PATH_NAME);
    }

    @Test
    void GIVEN_the_ultimate_config_WHEN_kernel_starts_THEN_services_starts_with_env_set() throws Exception {

        // start logWatcher with a separate thread
        new Thread(getLogWatcher()).start();

        // launch kernel
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", this.getClass().getResource("config.yaml").toString());
        kernel.launch();

        testGroup(0);
        System.out.println("Group 0 passed, now for the harder stuff");

        kernel.find("services", "main", "lifecycle", "run")
                .setValue("while true; do\n" + "        date; sleep 5; echo NEWMAIN\n" + "     " + "   done");
        testGroup(1);

        System.out.println("Group 1 passed, now merging delta.yaml");
        kernel.mergeInNewConfig("ID", System.currentTimeMillis(),
                (Map<Object, Object>) JSON.std.with(new YAMLFactory()).anyFrom(getClass().getResource("delta.yaml")))
                .get(60, TimeUnit.SECONDS);
        testGroup(2);
        System.out.println("Group 2 passed. We made it.");

        kernel.shutdown();
    }

    private Runnable getLogWatcher() {
        return () -> {
            try {

                File fileToWatch = new File(LOG_FILE_PATH_NAME);
                while (!fileToWatch.exists()) {
                    Thread.sleep(1000);
                }

                long lastKnownPosition = 0;
                while (true) {
                    Thread.sleep(1000);

                    try (RandomAccessFile randomAccessFile = new RandomAccessFile(fileToWatch, "r")) {
                        randomAccessFile.seek(lastKnownPosition);
                        String line;
                        while ((line = randomAccessFile.readLine()) != null) {
                            for (ExpectedMessage message : EXPECTED_MESSAGES) {
                                if (line.contains(message.pattern)) {
                                    if (++message.seen == 1) {
                                        System.out.println("KernelTest: Just saw " + message.message);
                                        COUNT_DOWN_LATCHES[message.group].countDown();
                                        System.out.println("\tCOUNT_DOWN_LATCHES[" + message.group + "]="
                                                + COUNT_DOWN_LATCHES[message.group].getCount());
                                    }
                                }
                            }
                        }
                        lastKnownPosition = randomAccessFile.getFilePointer();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        };
    }

    private void testGroup(int group) throws Exception {
        COUNT_DOWN_LATCHES[group].await(100, TimeUnit.SECONDS);

        for (ExpectedMessage pattern : EXPECTED_MESSAGES) {
            if (pattern.seen <= 0 && pattern.group == group) {
                fail("Didnt see: " + pattern.message);
            }
        }
    }

    @Test
    void GIVEN_dependency_will_error_out_WHEN_kernel_starts_THEN_main_restarts_after_dependency_retries()
            throws Exception {
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

        Kernel kernel = new Kernel();
        kernel.context.addGlobalStateChangeListener((EvergreenService service, State oldState, State newState) -> {
            if (expectedStateTransitionList.isEmpty()) {
                return;
            }

            ExpectedStateTransition expected = expectedStateTransitionList.peek();

            if (service.getName().equals(expected.serviceName) && oldState.equals(expected.was) && newState
                    .equals(expected.current)) {
                System.out.println(String.format("Just saw state event for service %s: %s => %s", expected.serviceName,
                        expected.was, expected.current));

                expectedStateTransitionList.pollFirst();

                if (expectedStateTransitionList.isEmpty()) {
                    assertionLatch.countDown();
                }
            }
        });

        kernel.parseArgs("-i", getClass().getResource("config_broken.yaml").toString());
        kernel.launch();
        assertionLatch.await(60, TimeUnit.SECONDS);

        kernel.shutdownNow();

        if (!expectedStateTransitionList.isEmpty()) {
            expectedStateTransitionList.forEach(e -> System.err.println(
                    String.format("Fail to see state event for service %s: %s=> %s", e.serviceName, e.was, e.current)));

            fail("Didn't see all expected state transitions");
        }
    }

    private static class ExpectedMessage {
        final int group;
        final String pattern;
        final String message;
        int seen;

        ExpectedMessage(int group, String pattern, String message, int count) {
            this.group = group;
            this.pattern = pattern;
            this.message = message;
            seen = 1 - count;
        }

        ExpectedMessage(int group, String pattern, String message) {
            this(group, pattern, message, 1);
        }
    }

    private static class ExpectedStateTransition {
        final String serviceName;
        final State was;
        final State current;

        ExpectedStateTransition(String name, State was, State current) {
            this.serviceName = name;
            this.was = was;
            this.current = current;
        }
    }
}
