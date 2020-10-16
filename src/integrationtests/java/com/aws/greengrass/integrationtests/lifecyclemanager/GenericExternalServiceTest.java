/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.lifecyclemanager;

import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.integrationtests.util.SudoUtil.assumeCanSudoShell;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


class GenericExternalServiceTest extends BaseITCase {

    private Kernel kernel;

    @BeforeEach
    void beforeEach() {
        kernel = new Kernel();
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_service_config_with_broken_skipif_config_WHEN_launch_service_THEN_service_moves_to_error_state()
            throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("skipif_broken.yaml").toString());

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
    void GIVEN_service_with_timeout_WHEN_timeout_expires_THEN_move_service_to_errored() throws InterruptedException {
        kernel.parseArgs("-i", getClass().getResource("service_timesout.yaml").toString());
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
    void GIVEN_service_shutdown_with_timeout_WHEN_timeout_expires_THEN_service_still_closes()
            throws InterruptedException, ServiceLoadException, TimeoutException, ExecutionException {
        kernel.parseArgs("-i", getClass().getResource("service_shutdown_timesout.yaml").toString());
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

        kernel.parseArgs("-i", getClass().getResource("service_with_dynamic_config.yaml").toString());
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
        Topic customConfigTopic = service.getServiceConfig().find(PARAMETERS_CONFIG_KEY, "my_custom_key");
        customConfigTopic.subscribe(customConfigWatcher);

        customConfigTopic.withValue("my_custom_initial_value");
        topicUpdateProcessedFuture.get();

        assertEquals(State.RUNNING, service.getState());

        verify(service, times(0)).requestReinstall();
        verify(service, times(0)).requestRestart();
    }

    @Test
    void GIVEN_running_service_WHEN_install_config_changes_THEN_service_reinstalls() throws Exception {
        kernel.parseArgs("-i", getClass().getResource("service_with_dynamic_config.yaml").toString());
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
        kernel.parseArgs("-i", getClass().getResource("service_with_dynamic_config.yaml").toString());
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
        kernel.parseArgs("-i", getClass().getResource("service_with_dynamic_config.yaml").toString());
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
        kernel.parseArgs("-i", getClass().getResource("service_with_dynamic_config.yaml").toString());
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
        kernel.parseArgs("-i", getClass().getResource("service_with_just_bootstrap.yaml").toString()).launch();

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
        kernel.parseArgs("-i", getClass().getResource("service_with_just_bootstrap.yaml").toString()).launch();

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

    @EnabledOnOs(OS.LINUX)
    @Test
    void GIVEN_linux_default_user_WHEN_runs_THEN_runs_with_default_user() throws Exception {
        runPosixUserShellTest("config_run_with_user.yaml", "123456");

    }

    @EnabledOnOs(OS.LINUX)
    @Test
    void GIVEN_linux_default_shell_WHEN_runs_THEN_runs_with_default_shell() throws Exception {
        runPosixUserShellTest("config_run_with_user_shell.yaml", "123456");
    }

    @EnabledOnOs(OS.LINUX)
    @Test
    void GIVEN_linux_requires_privilege_WHEN_runs_THEN_runs_with_root() throws Exception {
        runPosixUserShellTest("config_run_with_privilege.yaml", "0");
    }

    private void runPosixUserShellTest(String file, String expectedUid) throws Exception {
        kernel.parseArgs("-i", getClass().getResource(file).toString());

        // skip when running as a user that cannot sudo to shell
        assumeCanSudoShell(kernel);

        // create file for test to write UID into
        File testFile = File.createTempFile("user-test", ".txt");
        testFile.deleteOnExit();
        assertTrue(testFile.setWritable(true, false), "could not set test file to be writable");
        GenericExternalService service = (GenericExternalService) kernel.locate("echo_service");
        service.getServiceConfig().find(SETENV_CONFIG_NAMESPACE, "output_path").withValue(testFile.getAbsolutePath());

        CountDownLatch main = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((s, oldState, newState) -> {
            if (s.getName().equals("main") && newState.equals(State.FINISHED)) {
                main.countDown();
            }
        });
        kernel.launch();

        assertTrue(main.await(10, TimeUnit.SECONDS));

        String user = Files.newBufferedReader(testFile.toPath()).readLine();
        assertEquals(expectedUid, user);
    }
}
