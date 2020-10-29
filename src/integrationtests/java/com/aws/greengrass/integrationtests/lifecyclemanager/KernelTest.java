/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class KernelTest extends BaseITCase {
    private static final ExpectedStdoutPattern[] EXPECTED_MESSAGES =
            {new ExpectedStdoutPattern(0, "MAIN IS RUNNING", "Main service"),
                    new ExpectedStdoutPattern(0, "tick-tock", "ticktock did not execute 3 times", 3),
                    new ExpectedStdoutPattern(0, "ANSWER=42", "global setenv"),
                    new ExpectedStdoutPattern(0, "JUSTME=fancy a spot of tea?", "local setenv in main service"),
                    new ExpectedStdoutPattern(1, "NEWMAIN", "Assignment to 'run' script'")};

    private static final Map<Integer, CountDownLatch> COUNT_DOWN_LATCHES = new HashMap<>();
    private Kernel kernel;

    @BeforeAll
    static void beforeAll() {
        for (ExpectedStdoutPattern pattern : EXPECTED_MESSAGES) {
            CountDownLatch existingCdl = COUNT_DOWN_LATCHES.get(pattern.group);
            COUNT_DOWN_LATCHES.put(pattern.group,
                    new CountDownLatch(existingCdl == null ? 1 : (int) (existingCdl.getCount() + 1)));
        }
    }

    @AfterAll
    static void afterAll() {
        for (ExpectedStdoutPattern pattern : EXPECTED_MESSAGES) {
            pattern.reset();
        }
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_config_path_not_given_WHEN_kernel_launches_THEN_load_empty_main_service() throws Exception {
        // launch kernel without config arg
        kernel = new Kernel().parseArgs().launch();

        // verify
        GreengrassService mainService = kernel.locate("main");
        assertNotNull(mainService);
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    @Test
    void GIVEN_expected_stdout_patterns_WHEN_kernel_launches_THEN_all_expected_patterns_are_seen() throws Exception {

        // launch kernel
        kernel = new Kernel();

        try (AutoCloseable l = getLogListener()) {
            kernel.parseArgs("-i", this.getClass().getResource("config.yaml").toString());
            kernel.launch();

            testGroup(0);
            System.out.println("Group 0 passed, now for the harder stuff");

            kernel.findServiceTopic("main").find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC).withValue("echo NEWMAIN");
            testGroup(1);

            System.out.println("Group 1 passed");
        } finally {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_expected_stdout_patterns_WHEN_kernel_is_restarted_THEN_all_expected_patterns_are_seen()
            throws Exception {

        // add log listener to verify stdout pattern
        kernel = new Kernel();
        try (AutoCloseable l = getLogListener()) {
            kernel.parseArgs("-i", this.getClass().getResource("config.yaml").toString());
            // launch kernel 1st time
            kernel.launch();

            testGroup(0);

            kernel.shutdown();

            // reset pattern and countdown latches
            for (ExpectedStdoutPattern pattern : EXPECTED_MESSAGES) {
                pattern.reset();
                CountDownLatch existingCdl = COUNT_DOWN_LATCHES.get(pattern.group);
                COUNT_DOWN_LATCHES.put(pattern.group, new CountDownLatch(existingCdl == null ? 1 : (int) (existingCdl.getCount() + 1)));
            }

            // launch kernel 2nd time with empty arg but same root dir, as specified in the base IT case
            kernel = new Kernel().parseArgs().launch();
            testGroup(0);
        } finally {
            kernel.shutdown();
        }
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private AutoCloseable getLogListener() {
        return TestUtils.createCloseableLogListener(structuredLogMessage -> {
            String stdoutStr = structuredLogMessage.getContexts().get("stdout");

            if (stdoutStr == null || stdoutStr.length() == 0) {
                return;
            }

            for (ExpectedStdoutPattern expectedStdoutPattern : EXPECTED_MESSAGES) {
                if (stdoutStr.contains(expectedStdoutPattern.pattern) && --expectedStdoutPattern.count == 0) {
                    System.out.println(String.format("KernelTest: Just saw stdout pattern: '%s' for '%s'.",
                            expectedStdoutPattern.pattern, expectedStdoutPattern.message));

                    COUNT_DOWN_LATCHES.get(expectedStdoutPattern.group).countDown();

                    System.out.println("\tCOUNT_DOWN_LATCHES[" + expectedStdoutPattern.group + "]=" + COUNT_DOWN_LATCHES
                            .get(expectedStdoutPattern.group).getCount());
                }
            }
        });
    }

    private void testGroup(int group) throws Exception {
        COUNT_DOWN_LATCHES.get(group).await(30, TimeUnit.SECONDS);

        for (ExpectedStdoutPattern pattern : EXPECTED_MESSAGES) {
            if (pattern.count > 0 && pattern.group == group) {
                fail("Didn't see: " + pattern.message);
            }
        }
    }

    @Test
    void GIVEN_service_install_always_fail_WHEN_kernel_launches_THEN_service_go_broken_state() throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("config_install_error.yaml").toString());
        kernel.launch();

        CountDownLatch serviceBroken = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("installerror") && newState.equals(State.BROKEN)) {
                serviceBroken.countDown();
            }
        });
        assertTrue(serviceBroken.await(10, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_service_install_broken_WHEN_kernel_launches_with_fix_THEN_service_install_succeeds() throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("config_install_error.yaml").toString());
        kernel.launch();

        CountDownLatch serviceBroken = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("installerror") && newState.equals(State.BROKEN)) {
                serviceBroken.countDown();
            }
        });
        assertTrue(serviceBroken.await(10, TimeUnit.SECONDS));

        // merge in a new config that fixes the installation error
        kernel.getConfig().read(getClass().getResource("config_install_succeed_partial.yaml").toString());

        CountDownLatch serviceInstalled = new CountDownLatch(1);

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("installerror") && newState.equals(State.INSTALLED)) {
                serviceInstalled.countDown();
            }
        });
        assertTrue(serviceInstalled.await(15, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_service_install_fail_retry_succeed_WHEN_kernel_launches_THEN_service_install_succeeds()
            throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("config_install_error_retry.yaml").toString());
        kernel.launch();

        CountDownLatch serviceRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("installErrorRetry") && newState.equals(State.INSTALLED)) {
                serviceRunning.countDown();
            }
        });
        assertTrue(serviceRunning.await(15, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_service_startup_always_fail_WHEN_kernel_launches_THEN_service_go_broken_state() throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("config_startup_error.yaml").toString());
        kernel.launch();

        CountDownLatch serviceBroken = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("startupError") && newState.equals(State.BROKEN)) {
                serviceBroken.countDown();
            }
        });
        assertTrue(serviceBroken.await(15, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_service_startup_fail_retry_succeed_WHEN_kernel_launches_THEN_service_startup_succeeds()
            throws Exception {
        kernel = new Kernel();
        kernel.parseArgs("-i", getClass().getResource("config_startup_error_retry.yaml").toString());
        kernel.launch();

        CountDownLatch serviceRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("startupErrorRetry") && newState.equals(State.RUNNING)) {
                serviceRunning.countDown();
            }
        });
        assertTrue(serviceRunning.await(15, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_expected_state_transitions_WHEN_services_error_out_THEN_all_expectations_should_be_seen()
            throws Exception {
        LinkedList<ExpectedStateTransition> expectedStateTransitionList = new LinkedList<>(
                Arrays.asList(new ExpectedStateTransition("installErrorRetry", State.NEW, State.ERRORED, 0),
                        new ExpectedStateTransition("installErrorRetry", State.ERRORED, State.NEW, 0),
                        new ExpectedStateTransition("installErrorRetry", State.NEW, State.INSTALLED, 0),

                        // main service doesn't start until dependency ready
                        new ExpectedStateTransition("runErrorRetry", State.STARTING, State.RUNNING, 1),
                        new ExpectedStateTransition("main", State.STARTING, State.RUNNING, 1),

                        // runErrorRetry restart on error
                        new ExpectedStateTransition("runErrorRetry", State.RUNNING, State.ERRORED, 1),
                        new ExpectedStateTransition("runErrorRetry", State.ERRORED, State.STOPPING, 1),
                        new ExpectedStateTransition("runErrorRetry", State.STOPPING, State.INSTALLED, 1),

                        // main service restart on dependency error
                        new ExpectedStateTransition("runErrorRetry", State.RUNNING, State.BROKEN, 2),
                        new ExpectedStateTransition("main", null, State.FINISHED, 2)));

        CountDownLatch assertionLatch = new CountDownLatch(1);

        kernel = new Kernel();
        List<ExpectedStateTransition> actualTransitions = new LinkedList<>();
        AtomicInteger currentGroup = new AtomicInteger();
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            actualTransitions
                    .add(new ExpectedStateTransition(service.getName(), oldState, newState, currentGroup.get()));
            if (expectedStateTransitionList.isEmpty()) {
                return;
            }

            expectedStateTransitionList.stream().filter(x -> x.group == currentGroup.get() && !x.seen)
                    .filter(expected -> service.getName().equals(expected.serviceName) && (oldState.equals(expected.was)
                            || expected.was == null) && newState.equals(expected.current)).forEach(expected -> {
                LogManager.getLogger(getClass())
                        .info("Just saw state event for service {}: {} => {}", expected.serviceName, expected.was,
                                expected.current);
                expected.seen = true;
            });
            if (expectedStateTransitionList.stream().noneMatch(x -> x.group == currentGroup.get() && !x.seen)) {
                currentGroup.getAndIncrement();
            }
            expectedStateTransitionList.removeIf(x -> x.seen);
            if (expectedStateTransitionList.isEmpty()) {
                assertionLatch.countDown();
            }
        });

        kernel.parseArgs("-i", getClass().getResource("config_broken.yaml").toString());
        kernel.launch();
        assertionLatch.await(30, TimeUnit.SECONDS);

        kernel.shutdown();

        if (!expectedStateTransitionList.isEmpty()) {
            expectedStateTransitionList.stream().filter(x -> !x.seen).forEach(e -> System.err.println(
                    String.format("Fail to see state event for service %s: %s=> %s", e.serviceName, e.was, e.current)));

            System.err.println("\n\nDid see: ");
            actualTransitions.forEach(System.err::println);
            fail("Didn't see all expected state transitions");
        }
    }

    private static class ExpectedStdoutPattern {
        final int group;
        final String pattern;
        final String message;
        final int initialCount;
        int count;

        ExpectedStdoutPattern(int group, String pattern, String message, int count) {
            this.group = group;
            this.pattern = pattern;
            this.message = message;
            this.count = count;
            initialCount = count;
        }

        ExpectedStdoutPattern(int group, String pattern, String message) {
            this(group, pattern, message, 1);
        }

        void reset() {
            count = initialCount;
        }
    }

    @ToString
    @EqualsAndHashCode
    public static class ExpectedStateTransition {
        final String serviceName;
        final State was;
        final State current;
        final int group;
        boolean seen;

        ExpectedStateTransition(String name, State was, State current) {
            this(name, was, current, 0);
        }

        ExpectedStateTransition(String name, State was, State current, int group) {
            this.serviceName = name;
            this.was = was;
            this.current = current;
            this.group = group;
        }
    }
}
