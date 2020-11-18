/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.ConfigurationWriter;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
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
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, this.getClass().getResource("config.yaml"));
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
            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, this.getClass().getResource("config.yaml"));
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
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, this.getClass().getResource("config_install_error.yaml"));
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
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("config_install_error.yaml"));
        kernel.launch();

        CountDownLatch serviceBroken = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("installerror") && newState.equals(State.BROKEN)) {
                serviceBroken.countDown();
            }
        });
        assertTrue(serviceBroken.await(10, TimeUnit.SECONDS));

        // merge in a new config that fixes the installation error
        kernel.getConfig().mergeMap(System.currentTimeMillis(), ConfigPlatformResolver.resolvePlatformMap(
                getClass().getResource("config_install_succeed_partial.yaml")));

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
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("config_install_error_retry.yaml"));
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
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("config_startup_error.yaml"));
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
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("config_startup_error_retry.yaml"));
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

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                this.getClass().getResource("config_broken.yaml"));
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

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_running_WHEN_truncate_tlog_and_shutdown_THEN_tlog_consistent_with_config() throws Exception {
        kernel = new Kernel().parseArgs().launch();
        Configuration config = kernel.getConfig();
        Context context = kernel.getContext();
        Path configPath = tempRootDir.resolve("config");

        Topic testTopic = config.lookup("testTopic").withValue("initial");
        KernelLifecycle kernelLifecycle = context.get(KernelLifecycle.class);
        context.runOnPublishQueueAndWait(() -> {
            // make truncate run by setting a small limit
            kernelLifecycle.getTlog().withMaxEntries(1);
            testTopic.withValue("triggering truncate");
            // immediately queue a task to increase max size to prevent repeated truncation
            context.runOnPublishQueue(() -> kernelLifecycle.getTlog().withMaxEntries(10000));
        });
        // wait for things to complete
        CountDownLatch startupCdl = new CountDownLatch(1);
        context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.FINISHED)) {
                startupCdl.countDown();
            }
        });
        startupCdl.await(30, TimeUnit.SECONDS);
        // shutdown to stop config/tlog changes
        kernel.shutdown();
        Configuration tlogConfig = ConfigurationReader.createFromTLog(context, configPath.resolve("config.tlog"));
        assertEquals("triggering truncate", tlogConfig.find("testTopic").getOnce());
        // data type may be different when recreating tlog
        // using this to coerce to string for comparison
        assertEqualsDeepMap(config.toPOJO(), tlogConfig.toPOJO());
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_kernel_running_WHEN_truncate_tlog_and_shutdown_THEN_tlog_consistent_with_non_truncated_tlog()
            throws Exception {
        kernel = new Kernel();
        Configuration config = kernel.getConfig();
        Context context = kernel.getContext();
        Path configPath = tempRootDir.resolve("config");
        Files.createDirectories(configPath);

        kernel.writeEffectiveConfigAsTransactionLog(configPath.resolve("full.tlog"));
        ConfigurationWriter.logTransactionsTo(config, configPath.resolve("full.tlog")).flushImmediately(true);
        kernel.parseArgs().launch();

        Topic testTopic = config.lookup("testTopic").withValue("initial");
        KernelLifecycle kernelLifecycle = context.get(KernelLifecycle.class);
        context.runOnPublishQueueAndWait(() -> {
            // make truncate run by setting a small limit
            kernelLifecycle.getTlog().withMaxEntries(1);
            testTopic.withValue("triggering truncate");
            // immediately queue a task to increase max size to prevent repeated truncation
            context.runOnPublishQueue(() -> kernelLifecycle.getTlog().withMaxEntries(10000));
        });
        // wait for things to complete
        CountDownLatch startupCdl = new CountDownLatch(1);
        context.addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.FINISHED)) {
                startupCdl.countDown();
            }
        });
        startupCdl.await(30, TimeUnit.SECONDS);
        // shutdown to stop config/tlog changes
        kernel.shutdown();
        Configuration fullConfig = ConfigurationReader.createFromTLog(context, configPath.resolve("full.tlog"));
        Configuration compressedConfig = ConfigurationReader.createFromTLog(context, configPath.resolve("config.tlog"));
        assertEquals("triggering truncate", compressedConfig.find("testTopic").getOnce());
        assertThat(fullConfig.toPOJO(), is(compressedConfig.toPOJO()));
    }

    /**
     * Assert two nested Map<String, Object> is equal. Leaf objects are Coerce.toString before comparison
     */
    private void assertEqualsDeepMap(Map<String, Object> expected, Map<String, Object> actual) {
        for (Map.Entry<String, Object> expectedEntry : expected.entrySet()) {
            String key = expectedEntry.getKey();
            Object expectedVal = expectedEntry.getValue();
            Object actualVal = actual.get(key);
            if (expectedVal == null) {
                assertNull(actualVal);
            } else {
                if (expected.getClass().isInstance(expectedVal)) {
                    assertTrue(expected.getClass().isInstance(actualVal));
                    assertEqualsDeepMap((Map<String, Object>) expectedVal, (Map<String, Object>) actualVal);
                } else {
                    assertEquals(Coerce.toString(expectedVal), Coerce.toString(actualVal));
                }
            }
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
