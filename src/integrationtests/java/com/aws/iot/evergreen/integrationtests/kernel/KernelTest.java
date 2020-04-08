/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Log4jLogEventBuilder;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class KernelTest extends BaseITCase {
    private static final ExpectedStdoutPattern[] EXPECTED_MESSAGES =
            {new ExpectedStdoutPattern(0, "MAIN IS RUNNING", "Main service"),
                    //new ExpectedStdoutPattern("docs.docker.com/", "docker hello world"),
                    new ExpectedStdoutPattern(0, "tick-tock", "periodic", 3),
                    new ExpectedStdoutPattern(0, "ANSWER=42", "global setenv"),
                    new ExpectedStdoutPattern(0, "EVERGREEN_UID=", "generated unique token"),
                    new ExpectedStdoutPattern(0, "version: 0.12.1", "moquette mqtt server"),
                    new ExpectedStdoutPattern(0, "JUSTME=fancy a spot of tea?", "local setenv in main service"),
                    new ExpectedStdoutPattern(1, "NEWMAIN", "Assignment to 'run' script'"),
                    new ExpectedStdoutPattern(2, "JUSTME=fancy a spot of coffee?", "merge yaml"),
                    new ExpectedStdoutPattern(2, "I'm Frodo", "merge adding dependency")};

    private static final CountDownLatch[] COUNT_DOWN_LATCHES =
            {new CountDownLatch(6), new CountDownLatch(1), new CountDownLatch(2)};

    @Test
    void GIVEN_invalid_command_line_argument_WHEN_kernel_parseArgs_THEN_throw_RuntimeException() {
        Kernel kernel = new Kernel();
        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> kernel.parseArgs("-xyznonsense", "nonsense"));
        assertTrue(thrown.getMessage().contains("Undefined command line argument"));
    }

    @Test
    void GIVEN_create_path_fail_WHEN_kernel_parseArgs_THEN_throw_RuntimeException() throws Exception {
        // Make the root path not writeable so the create path method will fail
        Files.setPosixFilePermissions(tempRootDir, PosixFilePermissions.fromString("r-x------"));
        Kernel kernel = new Kernel();

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> kernel.parseArgs("-i", this.getClass().getResource("config.yaml").toString()));
        assertTrue(thrown.getMessage().contains("Fail to create the path"));
    }

    @Test
    void GIVEN_config_missing_main_WHEN_kernel_launches_THEN_throw_RuntimeException() {
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", this.getClass().getResource("config_missing_main.yaml").toString());
        assertThrows(RuntimeException.class, () -> kernel.launch());
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    @Test
    void GIVEN_expected_stdout_patterns_WHEN_kernel_launches_THEN_all_expected_patterns_are_seen() throws Exception {

        // add log listener to verify stdout pattern
        Consumer<EvergreenStructuredLogMessage> logListener = getLogListener();
        Log4jLogEventBuilder.addGlobalListener(logListener);

        // launch kernel
        Kernel kernel = new Kernel();
        kernel.parseArgs("-i", this.getClass().getResource("config.yaml").toString());
        kernel.launch();

        testGroup(0);
        System.out.println("Group 0 passed, now for the harder stuff");

        kernel.find("services", "main", "lifecycle", "run")
                .setValue("while true; do\ndate; sleep 5; echo NEWMAIN\ndone");
        testGroup(1);

        System.out.println("Group 1 passed, now merging delta.yaml");
        kernel.mergeInNewConfig("ID", System.currentTimeMillis(),
                (Map<Object, Object>) JSON.std.with(new YAMLFactory()).anyFrom(getClass().getResource("delta.yaml")))
                .get(60, TimeUnit.SECONDS);
        testGroup(2);
        System.out.println("Group 2 passed. We made it.");

        // clean up
        Log4jLogEventBuilder.removeGlobalListener(logListener);

        kernel.shutdown();
    }

    private Consumer<EvergreenStructuredLogMessage> getLogListener() {
        return evergreenStructuredLogMessage -> {
            String stdoutStr = evergreenStructuredLogMessage.getContexts().get("stdout");

            if (stdoutStr == null || stdoutStr.length() == 0) {
                return;
            }

            for (ExpectedStdoutPattern expectedStdoutPattern : EXPECTED_MESSAGES) {
                if (stdoutStr.contains(expectedStdoutPattern.pattern) && --expectedStdoutPattern.count == 0) {
                    System.out.println(String.format("KernelTest: Just saw stdout pattern: '%s' for '%s'.",
                            expectedStdoutPattern.pattern, expectedStdoutPattern.message));

                    COUNT_DOWN_LATCHES[expectedStdoutPattern.group].countDown();

                    System.out.println("\tCOUNT_DOWN_LATCHES[" + expectedStdoutPattern.group + "]="
                            + COUNT_DOWN_LATCHES[expectedStdoutPattern.group].getCount());
                }
            }
        };
    }

    private void testGroup(int group) throws Exception {
        COUNT_DOWN_LATCHES[group].await(100, TimeUnit.SECONDS);

        for (ExpectedStdoutPattern pattern : EXPECTED_MESSAGES) {
            if (pattern.count > 0 && pattern.group == group) {
                fail("Didn't see: " + pattern.message);
            }
        }
    }

    @Test
    void GIVEN_expected_state_transitions_WHEN_services_error_out_THEN_all_expectations_should_be_seen()
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
                        new ExpectedStateTransition("runErrorRetry", State.STOPPING, State.INSTALLED),

                        // main service restart on dependency error
                        new ExpectedStateTransition("runErrorRetry", State.RUNNING, State.ERRORED),
                        new ExpectedStateTransition("main", State.RUNNING, State.STOPPING),
                        new ExpectedStateTransition("runErrorRetry", State.INSTALLED, State.RUNNING),
                        new ExpectedStateTransition("main", State.INSTALLED, State.RUNNING)));

        CountDownLatch assertionLatch = new CountDownLatch(1);

        Kernel kernel = new Kernel();
        kernel.context.addGlobalStateChangeListener(
                (EvergreenService service, State oldState, State newState, boolean latest) -> {
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

        kernel.shutdown();

        if (!expectedStateTransitionList.isEmpty()) {
            expectedStateTransitionList.forEach(e -> System.err.println(
                    String.format("Fail to see state event for service %s: %s=> %s", e.serviceName, e.was, e.current)));

            fail("Didn't see all expected state transitions");
        }
    }

    private static class ExpectedStdoutPattern {
        final int group;
        final String pattern;
        final String message;
        int count;

        ExpectedStdoutPattern(int group, String pattern, String message, int count) {
            this.group = group;
            this.pattern = pattern;
            this.message = message;
            this.count = count;
        }

        ExpectedStdoutPattern(int group, String pattern, String message) {
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
