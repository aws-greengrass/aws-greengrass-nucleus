/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.kernel;

import com.aws.iot.evergreen.config.Subscriber;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.kernel.EvergreenService.CUSTOM_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


class GenericExternalServiceTest extends BaseITCase {

    private Kernel kernel;

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_service_config_with_broken_skipif_config_WHEN_launch_service_THEN_service_moves_to_error_state()
            throws Throwable {
        // GIVEN
        kernel = new Kernel();
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
        kernel = new Kernel();
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
    void GIVEN_service_with_dynamically_loaded_config_WHEN_dynamic_config_changes_THEN_service_does_not_restart()
            throws Exception {
        kernel = new Kernel();
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
        Topic customConfigTopic = service.getServiceConfig().find(CUSTOM_CONFIG_NAMESPACE, "my_custom_key");
        customConfigTopic.subscribe(customConfigWatcher);

        customConfigTopic.withValue("my_custom_initial_value");
        topicUpdateProcessedFuture.get();

        assertEquals(State.RUNNING, service.getState());

        verify(service, times(0)).requestReinstall();
        verify(service, times(0)).requestRestart();
    }

    @Test
    void GIVEN_running_service_WHEN_install_config_changes_THEN_service_reinstalls() throws Exception {
        kernel = new Kernel();
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
        kernel = new Kernel();
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
        kernel = new Kernel();
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
        kernel = new Kernel();
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
}
