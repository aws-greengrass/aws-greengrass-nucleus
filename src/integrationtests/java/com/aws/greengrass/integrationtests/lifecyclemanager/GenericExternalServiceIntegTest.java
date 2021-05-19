/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.unix.linux.Cgroup;
import com.aws.greengrass.util.platforms.unix.linux.LinuxSystemResourceController;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SYSTEM_RESOURCE_LIMITS_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.SudoUtil.assumeCanSudoShell;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.createCloseableLogListener;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


class GenericExternalServiceIntegTest extends BaseITCase {

    private Kernel kernel;

    static Stream<Arguments> posixTestUserConfig() {
        return Stream.of(
                arguments("config_run_with_user.yaml", "nobody", "nobody"),
                arguments("config_run_with_user_shell.yaml", "nobody", "nobody"),
                arguments("config_run_with_privilege.yaml", "nobody", "root")
        );
    }

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_service_config_with_broken_skipif_config_WHEN_launch_service_THEN_service_moves_to_error_state()
            throws Throwable {
        // GIVEN
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, getClass().getResource("skipif_broken.yaml"));

        CountDownLatch testErrored = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("test") && newState.equals(State.ERRORED)) {
                testErrored.countDown();
            }
        });

        // WHEN
        kernel.launch();

        // THEN
        assertTrue(testErrored.await(10, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_service_with_timeout_WHEN_timeout_expires_THEN_move_service_to_errored() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, getClass().getResource("service_timesout.yaml"));
        kernel.launch();
        CountDownLatch ServicesAErroredLatch = new CountDownLatch(1);
        CountDownLatch ServicesBErroredLatch = new CountDownLatch(1);
        // service sleeps for 120 seconds during startup and timeout is 1 second, service should transition to errored
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("ServiceA".equals(service.getName()) && State.ERRORED.equals(newState)) {
                ServicesAErroredLatch.countDown();
            }
            if ("ServiceB".equals(service.getName()) && State.ERRORED.equals(newState)) {
                ServicesBErroredLatch.countDown();
            }
        });

        assertTrue(ServicesAErroredLatch.await(5, TimeUnit.SECONDS));
        assertTrue(ServicesBErroredLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_service_has_recovery_step_WHEN_recovery_logic_fixes_issue_THEN_service_recovers() throws Exception {
        Consumer<GreengrassLogMessage> logListener = null;
        try {
            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                    getClass().getResource("service_error_recovery_step_fixes_service.yaml"));

            CountDownLatch ServiceAErroredLatch = new CountDownLatch(1);
            CountDownLatch ServiceARunningLatch = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if ("ServiceA".equals(service.getName()) && State.ERRORED.equals(newState)) {
                    ServiceAErroredLatch.countDown();
                }
                if ("ServiceA".equals(service.getName()) && State.RUNNING.equals(newState)) {
                    ServiceARunningLatch.countDown();
                }
            });

            CountDownLatch recoveryLogicWorks = new CountDownLatch(1);
            logListener = m -> {
                if (m.getJSONMessage() != null && m.getJSONMessage()
                        .contains("Fixing ServiceA")) {
                    recoveryLogicWorks.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(logListener);

            kernel.launch();

            assertTrue(recoveryLogicWorks.await(15, TimeUnit.SECONDS));
            assertTrue(ServiceARunningLatch.await(15, TimeUnit.SECONDS));
            assertTrue(ServiceAErroredLatch.await(15, TimeUnit.SECONDS));
        } finally {
            if (logListener != null) {
                Slf4jLogAdapter.removeGlobalListener(logListener);
            }
        }
    }

    @Test
    void GIVEN_service_has_recovery_step_WHEN_recovery_logic_cannot_fix_issue_THEN_service_goes_to_broken_state()
            throws Exception {
        Consumer<GreengrassLogMessage> logListener = null;
        try {
            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                    getClass().getResource("service_error_recovery_step_does_not_fix_service.yaml"));

            CountDownLatch ServiceAErroredLatch = new CountDownLatch(2);
            CountDownLatch ServiceABrokenLatch = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if ("ServiceA".equals(service.getName()) && State.ERRORED.equals(newState)) {
                    ServiceAErroredLatch.countDown();
                }
                if ("ServiceA".equals(service.getName()) && State.BROKEN.equals(newState)) {
                    ServiceABrokenLatch.countDown();
                }
            });

            CountDownLatch recoveryLogicInvoked = new CountDownLatch(2);
            logListener = m -> {
                if (m.getJSONMessage() != null && m.getJSONMessage()
                        .contains("Not going to fix anything")) {
                    recoveryLogicInvoked.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(logListener);

            kernel.launch();

            assertTrue(recoveryLogicInvoked.await(15, TimeUnit.SECONDS));
            assertTrue(ServiceABrokenLatch.await(15, TimeUnit.SECONDS));
            assertTrue(ServiceAErroredLatch.await(15, TimeUnit.SECONDS));
        } finally {
            if (logListener != null) {
                Slf4jLogAdapter.removeGlobalListener(logListener);
            }
        }
    }

    @Test
    void GIVEN_service_has_recovery_step_WHEN_recovery_timeout_expires_THEN_move_service_to_broken_state_after_all_recovery_retries()
            throws Exception {
        Consumer<GreengrassLogMessage> logListener = null;
        try {
            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                    getClass().getResource("service_error_recovery_step_times_out.yaml"));

            CountDownLatch ServiceAErroredLatch = new CountDownLatch(2);
            CountDownLatch ServiceABrokenLatch = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if ("ServiceA".equals(service.getName()) && State.ERRORED.equals(newState)) {
                    ServiceAErroredLatch.countDown();
                }
                if ("ServiceA".equals(service.getName()) && State.BROKEN.equals(newState)) {
                    ServiceABrokenLatch.countDown();
                }
            });

            CountDownLatch recoveryExecutionTimeout = new CountDownLatch(2);
            logListener = m -> {
                if (m.getMessage() != null && m.getMessage()
                        .contains("Error recovery handler timed out after 1 seconds")) {
                    recoveryExecutionTimeout.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(logListener);

            kernel.launch();

            assertTrue(recoveryExecutionTimeout.await(15, TimeUnit.SECONDS));
            assertTrue(ServiceABrokenLatch.await(15, TimeUnit.SECONDS));
            assertTrue(ServiceAErroredLatch.await(15, TimeUnit.SECONDS));
        } finally {
            if (logListener != null) {
                Slf4jLogAdapter.removeGlobalListener(logListener);
            }
        }
    }

    @Test
    void GIVEN_service_shutdown_with_timeout_WHEN_timeout_expires_THEN_service_still_closes()
            throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_shutdown_timesout.yaml"));
        kernel.launch();
        CountDownLatch mainRunning = new CountDownLatch(1);
        // service sleeps for 120 seconds during shutdown and timeout is 1 second, service should transition to errored
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("main".equals(service.getName()) && State.RUNNING.equals(newState)) {
                mainRunning.countDown();
            }
        });

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));
        kernel.locate("main").close().get(20, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_service_with_dynamically_loaded_config_WHEN_dynamic_config_changes_THEN_service_does_not_restart()
            throws Exception {

        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_dynamic_config.yaml"));
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        GenericExternalService service = spy((GenericExternalService) kernel.locate("service_with_dynamic_config"));
        assertEquals(State.RUNNING, service.getState());

        CompletableFuture<Void> topicUpdateProcessedFuture = new CompletableFuture<>();
        Subscriber customConfigWatcher = (WhatHappened what, Topic t) -> {
            topicUpdateProcessedFuture.complete(null);
        };
        Topic customConfigTopic = service.getServiceConfig().find(CONFIGURATION_CONFIG_KEY, "my_custom_key");
        customConfigTopic.subscribe(customConfigWatcher);

        customConfigTopic.withValue("my_custom_initial_value");
        topicUpdateProcessedFuture.get();

        assertEquals(State.RUNNING, service.getState());

        verify(service, times(0)).requestReinstall();
        verify(service, times(0)).requestRestart();
    }

    @Test
    void GIVEN_running_service_WHEN_install_config_changes_THEN_service_reinstalls() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_dynamic_config.yaml"));
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        GenericExternalService service = spy((GenericExternalService) kernel.locate("service_with_dynamic_config"));
        assertEquals(State.RUNNING, service.getState());

        CountDownLatch serviceReinstalled = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((serviceToListenTo, oldState, newState) -> {
            if ("service_with_dynamic_config".equals(serviceToListenTo.getName()) && State.NEW.equals(newState)) {
                serviceReinstalled.countDown();
            }
        });
        service.getServiceConfig().find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "install")
                .withValue("echo \"Reinstalling service_with_dynamic_config\"");

        assertTrue(serviceReinstalled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_running_service_WHEN_version_config_changes_THEN_service_reinstalls() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_dynamic_config.yaml"));
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        GenericExternalService service = spy((GenericExternalService) kernel.locate("service_with_dynamic_config"));
        assertEquals(State.RUNNING, service.getState());

        CountDownLatch serviceReinstalled = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((serviceToListenTo, oldState, newState) -> {
            if ("service_with_dynamic_config".equals(serviceToListenTo.getName()) && State.NEW.equals(newState)) {
                serviceReinstalled.countDown();
            }
        });
        service.getServiceConfig().find(VERSION_CONFIG_KEY).withValue("1.0.1");

        assertTrue(serviceReinstalled.await(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_running_service_WHEN_run_config_changes_THEN_service_restarts() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_dynamic_config.yaml"));
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        GenericExternalService service = spy((GenericExternalService) kernel.locate("service_with_dynamic_config"));
        assertEquals(State.RUNNING, service.getState());

        CountDownLatch serviceRestarted = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((serviceToListenTo, oldState, newState) -> {
            if ("service_with_dynamic_config".equals(serviceToListenTo.getName()) && State.INSTALLED.equals(newState)) {
                serviceRestarted.countDown();
            }
        });
        service.getServiceConfig().find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, "run")
                .withValue("echo \"Rerunning " + "service_with_dynamic_config\" && sleep 100");

        assertTrue(serviceRestarted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_running_service_WHEN_setenv_config_changes_THEN_service_restarts() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_dynamic_config.yaml"));
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        GenericExternalService service = spy((GenericExternalService) kernel.locate("service_with_dynamic_config"));
        assertEquals(State.RUNNING, service.getState());

        CountDownLatch serviceRestarted = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((serviceToListenTo, oldState, newState) -> {
            if ("service_with_dynamic_config".equals(serviceToListenTo.getName()) && State.INSTALLED.equals(newState)) {
                serviceRestarted.countDown();
            }
        });
        service.getServiceConfig().find(SETENV_CONFIG_NAMESPACE, "my_env_var").withValue("var2");

        assertTrue(serviceRestarted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_bootstrap_command_WHEN_bootstrap_THEN_command_runs_and_returns_exit_code() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_just_bootstrap.yaml"));
        kernel.launch();

        CountDownLatch mainFinished = new CountDownLatch(1);

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.FINISHED)) {
                mainFinished.countDown();
            }
        });

        assertTrue(mainFinished.await(10, TimeUnit.SECONDS));

        GenericExternalService serviceWithJustBootstrap =
                (GenericExternalService) kernel.locate("service_with_just_bootstrap");

        assertEquals(147, serviceWithJustBootstrap.bootstrap());

        GenericExternalService serviceWithJustBootstrapAndConfiguredTimeout =
                (GenericExternalService) kernel.locate("service_with_just_bootstrap_and_timeout_configured");
        assertEquals(147, serviceWithJustBootstrapAndConfiguredTimeout.bootstrap());
    }

    @Test
    void GIVEN_bootstrap_command_WHEN_runs_longer_than_5_sec_THEN_timeout_exception_is_thrown() throws Exception {
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("service_with_just_bootstrap.yaml"));
        kernel.launch();

        CountDownLatch mainFinished = new CountDownLatch(1);

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.FINISHED)) {
                mainFinished.countDown();
            }
        });

        assertTrue(mainFinished.await(10, TimeUnit.SECONDS));

        GenericExternalService serviceWithJustBootstrapAndShouldTimeout =
                (GenericExternalService) kernel.locate("service_with_just_bootstrap_and_should_timeout");

        // this runs 5 seconds
        assertThrows(TimeoutException.class, serviceWithJustBootstrapAndShouldTimeout::bootstrap);
    }

    @EnabledOnOs({OS.LINUX, OS.MAC})
    @ParameterizedTest
    @MethodSource("posixTestUserConfig")
    void GIVEN_posix_default_user_WHEN_runs_THEN_runs_with_default_user(String file, String expectedInstallUser,
                                                                        String expectedRunUser)
            throws Exception {
        assumeTrue("root".equals(SystemUtils.USER_NAME), "test must be run as root as services run as different users"
                + " and write files to service work path");

        CountDownLatch countDownLatch = new CountDownLatch(2);
        // Set up stdout listener to capture stdout for verifying users
        List<String> stdouts = new CopyOnWriteArrayList<>();
        try (AutoCloseable l = createCloseableLogListener((m) -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null
                    && (messageOnStdout.contains("run as")
                        || messageOnStdout.contains("install as") )) {
                stdouts.add(messageOnStdout);
                countDownLatch.countDown();
            }
        })) {

            ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, getClass().getResource(file));

            // skip when running as a user that cannot sudo to shell
            assumeCanSudoShell(kernel);

            CountDownLatch main = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((s, oldState, newState) -> {
                if (s.getName().equals("main") && newState.equals(State.FINISHED)) {
                    main.countDown();
                }
            });
            kernel.launch();

            assertTrue(main.await(20, TimeUnit.SECONDS), "main finished");
            assertTrue(countDownLatch.await(20, TimeUnit.SECONDS), "expect log finished");
            assertThat(stdouts, hasItem(containsString(String.format("install as %s", expectedInstallUser))));
            assertThat(stdouts, hasItem(containsString(String.format("run as %s", expectedRunUser))));


            // get work path (workPath(service) sets permissions)
            Path echoServiceWorkPath = kernel.getNucleusPaths().workPath().resolve("echo_service");
            Path installedFile = echoServiceWorkPath.resolve("install-file");
            Path runFile = echoServiceWorkPath.resolve("run-file");

            assertThat(installedFile.toString(), installedFile.toFile(), anExistingFile());
            assertThat(runFile.toString(), runFile.toFile(), anExistingFile());

            assertThat(Files.getOwner(echoServiceWorkPath).getName(), is(expectedInstallUser));
            assertThat(Files.getOwner(installedFile).getName(), is(expectedInstallUser));
            assertThat(Files.getOwner(runFile).getName(), is(expectedRunUser));
        }
    }

    @EnabledOnOs({OS.LINUX})
    @Test
    void GIVEN_linux_resource_limits_WHEN_it_changes_THEN_component_runs_with_new_resource_limits() throws Exception {
        String componentName = "echo_service";
        // Run with no resource limit
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("config_run_with_user.yaml"));
        kernel.launch();

        // Run with nucleus default resource limit
        DeviceConfiguration deviceConfiguration = new DeviceConfiguration(kernel);
        deviceConfiguration.getRunWithTopic()
                .lookup(SYSTEM_RESOURCE_LIMITS_TOPICS, PlatformResolver.getOSInfo(), "memory").withValue(10240l);
        deviceConfiguration.getRunWithTopic()
                .lookup(SYSTEM_RESOURCE_LIMITS_TOPICS, PlatformResolver.getOSInfo(), "cpu").withValue(1.5);
        //Block until events are completed
        kernel.getContext().waitForPublishQueueToClear();

        assertResourceLimits(componentName, 10240l * 1024, 1.5);

        // Run with component resource limit
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, componentName, RUN_WITH_NAMESPACE_TOPIC,
                SYSTEM_RESOURCE_LIMITS_TOPICS, PlatformResolver.getOSInfo(), "memory").withValue(102400l);

        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, componentName, RUN_WITH_NAMESPACE_TOPIC,
                SYSTEM_RESOURCE_LIMITS_TOPICS, PlatformResolver.getOSInfo(), "cpu").withValue(0.5);
        //Block until events are completed
        kernel.getContext().waitForPublishQueueToClear();

        assertResourceLimits(componentName, 102400l * 1024, 0.5);

        // remove component resource limit
        kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, componentName, RUN_WITH_NAMESPACE_TOPIC,
                SYSTEM_RESOURCE_LIMITS_TOPICS).remove();
        kernel.getContext().waitForPublishQueueToClear();

        assertResourceLimits(componentName, 10240l * 1024, 1.5);
        // remove default resource limit
        deviceConfiguration.findRunWithDefaultSystemResourceLimits().remove();
        kernel.getContext().waitForPublishQueueToClear();

        assertSystemDefaultLimits(componentName);
    }

    private void assertSystemDefaultLimits(String componentName) throws Exception {
        long defaultCgroupMemoryLimit = 9223372036854771712l;
        int defaultCpuQuota = -1;

        byte[] buf1 = Files.readAllBytes(Cgroup.Memory.getComponentMemoryLimitPath(componentName));
        assertThat(String.valueOf(defaultCgroupMemoryLimit), equalTo(new String(buf1, StandardCharsets.UTF_8).trim()));

        byte[] buf2 = Files.readAllBytes(Cgroup.CPU.getComponentCpuQuotaPath(componentName));
        assertThat(String.valueOf(defaultCpuQuota), equalTo(new String(buf2, StandardCharsets.UTF_8).trim()));
    }

    private void assertResourceLimits(String componentName, long memory, double cpu) throws Exception {
        byte[] buf1 = Files.readAllBytes(Cgroup.Memory.getComponentMemoryLimitPath(componentName));
        assertThat(memory, equalTo(Long.parseLong(new String(buf1, StandardCharsets.UTF_8).trim())));

        byte[] buf2 = Files.readAllBytes(Cgroup.CPU.getComponentCpuQuotaPath(componentName));
        byte[] buf3 = Files.readAllBytes(Cgroup.CPU.getComponentCpuPeriodPath(componentName));

        int quota = Integer.parseInt(new String(buf2, StandardCharsets.UTF_8).trim());
        int period = Integer.parseInt(new String(buf3, StandardCharsets.UTF_8).trim());
        int expectedQuota = (int) (cpu * period);
        assertThat(expectedQuota, equalTo(quota));
    }

    void GIVEN_running_service_WHEN_pause_resume_requested_THEN_pause_resume_Service_and_freeze_thaw_cgroup(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, FileSystemException.class);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                getClass().getResource("long_running_services.yaml"));
        kernel.launch();

        CountDownLatch mainRunningLatch = new CountDownLatch(1);
        kernel.getMain().addStateSubscriber((WhatHappened what, Topic t) -> {
            if (Coerce.toEnum(State.class, t).isRunning()) {
                mainRunningLatch.countDown();
            }
        });

        // wait for main to run
        assertTrue(mainRunningLatch.await(60, TimeUnit.SECONDS), "main running");

        GenericExternalService component = (GenericExternalService) kernel.locate("sleeperA");
        assertThat(component.getState(), is(State.RUNNING));

        component.pause();
        assertTrue(component.isPaused());
        assertThat(getCgroupFreezerState(component.getServiceName()),
                anyOf(is(LinuxSystemResourceController.CgroupFreezerState.FROZEN),
                        is(LinuxSystemResourceController.CgroupFreezerState.FREEZING)));

        component.resume();
        assertFalse(component.isPaused());
        assertThat(getCgroupFreezerState(component.getServiceName()),
                is(LinuxSystemResourceController.CgroupFreezerState.THAWED));
    }

    // To be used on linux only
    private LinuxSystemResourceController.CgroupFreezerState getCgroupFreezerState(String serviceName)
            throws IOException {
        return LinuxSystemResourceController.CgroupFreezerState
                .valueOf(new String(Files.readAllBytes(Cgroup.Freezer.getCgroupFreezerStateFilePath(serviceName))
                        , StandardCharsets.UTF_8).trim());
    }
}
