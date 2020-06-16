/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.integrationtests.deployment;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GenericExternalService;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.deployment.DeploymentConfigMerger.DEPLOYMENT_SAFE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SETENV_CONFIG_NAMESPACE;
import static com.aws.iot.evergreen.kernel.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
class DeploymentConfigMergingTest extends BaseITCase {
    private Kernel kernel;
    private DeploymentConfigMerger deploymentConfigMerger;

    @BeforeEach
    void before(TestInfo testInfo) {
        System.out.println("Running test: " + testInfo.getDisplayName());
        kernel = new Kernel();
        deploymentConfigMerger = new DeploymentConfigMerger(kernel);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_kernel_running_with_some_config_WHEN_merge_simple_yaml_file_THEN_config_is_updated() throws Throwable {

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("config.yaml").toString());
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING) && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        });
        deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(),
                (Map<Object, Object>) JSON.std.with(new YAMLFactory()).anyFrom(getClass().getResource("delta.yaml")))
                .get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
        assertThat((String) kernel.findServiceTopic("main")
                        .find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC).getOnce(),
                containsString("echo Now we\\'re in phase 3"));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_changes_service_THEN_service_restarts_with_new_config()
            throws Throwable {

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING) && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        });
        deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SETENV_CONFIG_NAMESPACE, new HashMap<Object, Object>() {{
                        put("HELLO", "redefined");
                    }});
                }});
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
        assertEquals("redefined", kernel.findServiceTopic("main").find(SETENV_CONFIG_NAMESPACE, "HELLO").getOnce());
        assertThat((String) kernel.findServiceTopic("main")
                        .find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC).getOnce(),
                containsString("echo \"Running main\""));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_change_adding_dependency_THEN_dependent_service_starts_and_service_restarts()
            throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());

        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        CountDownLatch newServiceStarted = new CountDownLatch(1);

        // Check that new_service starts and then main gets restarted
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("new_service") && newState.equals(State.RUNNING)) {
                newServiceStarted.countDown();
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.getCount() == 0 && service.getName().equals("main") && newState.equals(State.RUNNING)
                    && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(EvergreenService::getName)
                .collect(Collectors.toList());
        serviceList.add("new_service");
        deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, serviceList);
                }});

                put("new_service", new HashMap<Object, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                            put("script", "sleep 60");
                        }});
                    }});
                }});
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(newServiceStarted.await(60, TimeUnit.SECONDS));
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_change_adding_nested_dependency_THEN_dependent_services_start_and_service_restarts()
            throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());

        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // WHEN
        CountDownLatch mainRestarted = new CountDownLatch(1);
        CountDownLatch newService2Started = new CountDownLatch(1);
        CountDownLatch newServiceStarted = new CountDownLatch(1);

        // Check that new_service2 starts, then new_service, and then main gets restarted
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("new_service2") && newState.equals(State.RUNNING)) {
                newService2Started.countDown();
            }
            if (newService2Started.getCount() == 0 && service.getName().equals("new_service") && newState
                    .equals(State.RUNNING)) {
                newServiceStarted.countDown();
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.getCount() == 0 && service.getName().equals("main") && newState.equals(State.RUNNING)
                    && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(EvergreenService::getName)
                .collect(Collectors.toList());
        serviceList.add("new_service");

        deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, serviceList);
                }});

                put("new_service", new HashMap<Object, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "sleep 60");
                    }});
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("new_service2"));
                }});

                put("new_service2", new HashMap<Object, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "sleep 60");
                    }});
                }});
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(newService2Started.await(60, TimeUnit.SECONDS));
        assertTrue(newServiceStarted.await(60, TimeUnit.SECONDS));
        assertTrue(mainRestarted.await(60, TimeUnit.SECONDS));
        assertThat(kernel.orderedDependencies().stream().map(EvergreenService::getName).collect(Collectors.toList()),
                containsInRelativeOrder("new_service2", "new_service", "main"));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_same_doc_happens_twice_THEN_second_merge_should_not_restart_services()
            throws Throwable {

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());

        HashMap<Object, Object> newConfig = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("main", new HashMap<Object, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("new_service"));
                }});

                put("new_service", new HashMap<Object, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "sleep 60");
                    }});
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("new_service2"));
                }});

                put("new_service2", new HashMap<Object, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "sleep 60");
                    }});
                }});
            }});
        }};

        // launch kernel
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        // do first merge
        AtomicBoolean mainRestarted = new AtomicBoolean(false);
        AtomicBoolean newService2Started = new AtomicBoolean(false);
        AtomicBoolean newServiceStarted = new AtomicBoolean(false);
        GlobalStateChangeListener listener = (service, oldState, newState) -> {
            if (service.getName().equals("new_service2") && newState.equals(State.RUNNING)) {
                newService2Started.set(true);
            }
            if (newService2Started.get() && service.getName().equals("new_service") && newState.equals(State.RUNNING)) {
                newServiceStarted.set(true);
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.get() && service.getName().equals("main") && newState.equals(State.RUNNING)
                    && oldState.equals(State.STARTING)) {
                mainRestarted.set(true);
            }
        };
        kernel.getContext().addGlobalStateChangeListener(listener);

        EvergreenService main = kernel.locate("main");
        deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), newConfig).get(60, TimeUnit.SECONDS);

        // Verify that first merge succeeded.
        assertEquals(State.RUNNING, main.getState());
        assertTrue(newService2Started.get());
        assertTrue(newServiceStarted.get());
        assertTrue(mainRestarted.get());
        assertThat(kernel.orderedDependencies().stream().map(EvergreenService::getName).collect(Collectors.toList()),
                containsInRelativeOrder("new_service2", "new_service", "main"));

        // WHEN
        AtomicBoolean stateChanged = new AtomicBoolean(false);
        listener = (service, oldState, newState) -> {
            System.err.println(
                    "State shouldn't change in merging the same config: " + service.getName() + " " + oldState + " => "
                            + newState);
            stateChanged.set(true);
        };

        kernel.getContext().addGlobalStateChangeListener(listener);

        // THEN
        // merge in the same config the second time
        // merge shouldn't block
        deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), newConfig).get(60, TimeUnit.SECONDS);

        // main and sleeperB should be running
        assertEquals(State.RUNNING, main.getState());

        assertFalse(stateChanged.get(), "State shouldn't change in merging the same config.");

        // remove listener
        kernel.getContext().removeGlobalStateChangeListener(listener);
    }

    @Test
    void GIVEN_kernel_running_services_WHEN_merge_removes_service_THEN_removed_service_is_closed() throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("long_running_services.yaml").toString());
        kernel.launch();

        CountDownLatch mainRunningLatch = new CountDownLatch(1);
        kernel.getMain().addStateSubscriber((WhatHappened what, Topic t) -> {
            if (((State) t.getOnce()).isRunning()) {
                mainRunningLatch.countDown();
            }
        });

        //wait for main to run
        assertTrue(mainRunningLatch.await(60, TimeUnit.SECONDS));

        Map<Object, Object> currentConfig = new HashMap<>(kernel.getConfig().toPOJO());
        Map<String, Map> servicesConfig = (Map<String, Map>) currentConfig.get(SERVICES_NAMESPACE_TOPIC);

        //removing all services in the current kernel config except sleeperB and main
        servicesConfig.keySet().removeIf(serviceName -> !"sleeperB".equals(serviceName) && !"main".equals(serviceName));
        List<String> dependencies =
                new ArrayList<>((List<String>) servicesConfig.get("main").get(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC));
        //removing main's dependency on sleeperA, Now sleeperA is an unused dependency
        dependencies.removeIf(s -> s.contains("sleeperA"));
        servicesConfig.get("main").put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, dependencies);
        // updating service B's run
        ((Map) servicesConfig.get("sleeperB").get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC))
                .put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "while true; do\n echo sleeperB_running; sleep 10\n done");

        Future<DeploymentResult> future =
                deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), currentConfig);
        AtomicBoolean isSleeperAClosed = new AtomicBoolean(false);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if ("sleeperA".equals(service.getName()) && newState.isClosable()) {
                isSleeperAClosed.set(true);
            }
        });

        EvergreenService main = kernel.locate("main");
        EvergreenService sleeperB = kernel.locate("sleeperB");
        // wait for merge to complete
        future.get(60, TimeUnit.SECONDS);
        //sleeperA should be closed
        assertTrue(isSleeperAClosed.get());
        // main and sleeperB should be running
        assertEquals(State.RUNNING, main.getState());
        assertEquals(State.RUNNING, sleeperB.getState());
        // ensure context finish all tasks
        kernel.getContext().runOnPublishQueueAndWait(() -> {});
        // ensuring config value for sleeperA is removed
        assertFalse(kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC).children.containsKey("sleeperA"));
        // ensure kernel no longer holds a reference of sleeperA
        assertThrows(ServiceLoadException.class, () -> kernel.locate("sleeperA"));

        List<String> orderedDependencies = kernel.orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).collect(Collectors.toList());

        assertEquals(Arrays.asList("sleeperB", "main"), orderedDependencies);
    }

    @Test
    void GIVEN_a_running_service_is_not_disruptable_WHEN_deployed_THEN_deployment_waits() throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("non_disruptable_service.yaml").toString());
        kernel.launch();

        CountDownLatch mainFinished = new CountDownLatch(1);
        kernel.getMain().addStateSubscriber((WhatHappened what, Topic t) -> {
            if (t.getOnce().equals(State.FINISHED)) {
                mainFinished.countDown();
            }
        });

        // wait for main to finish
        assertTrue(mainFinished.await(10, TimeUnit.SECONDS));

        Map<Object, Object> currentConfig = new HashMap<>(kernel.getConfig().toPOJO());

        Future<DeploymentResult> future =
                deploymentConfigMerger.mergeInNewConfig(testDeploymentDocument(), currentConfig);

        AtomicBoolean sawUpdatesCompleted = new AtomicBoolean();
        AtomicBoolean unsafeToUpdate = new AtomicBoolean();
        AtomicBoolean safeToUpdate = new AtomicBoolean();
        Consumer<EvergreenStructuredLogMessage> listener = (m) -> {
            if ("Yes! Updates completed".equals(m.getContexts().get("stdout"))) {
                sawUpdatesCompleted.set(true);
            }
            if ("Not SafeUpdate".equals(m.getContexts().get("stdout"))) {
                unsafeToUpdate.set(true);
            }
            if ("Safe Update".equals(m.getContexts().get("stdout"))) {
                safeToUpdate.set(true);
            }
        };

        try {
            Slf4jLogAdapter.addGlobalListener(listener);
            assertThrows(TimeoutException.class, () -> future.get(2, TimeUnit.SECONDS),
                    "Merge should not happen within 2 seconds");
            assertTrue(unsafeToUpdate.get(), "Service should have been checked if it is safe to update immediately");
            assertFalse(safeToUpdate.get(), "Service should not yet be safe to update");

            future.get(20, TimeUnit.SECONDS);
            assertTrue(safeToUpdate.get(), "Service should have been rechecked and be safe to update");
            assertTrue(sawUpdatesCompleted.get(), "Service should have been called when the update was done");
        } finally {
            Slf4jLogAdapter.removeGlobalListener(listener);
        }
    }

    @Test
    void GIVEN_service_running_with_rollback_safe_param_WHEN_rollback_THEN_rollback_safe_param_not_updated(
            ExtensionContext context) throws Throwable {

        ignoreExceptionUltimateCauseWithMessage(context, "Service sleeperB in broken state after deployment");

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("long_running_services.yaml").toString());

        kernel.launch();

        Configuration config = kernel.getConfig();
        config.lookup(SERVICES_NAMESPACE_TOPIC, "sleeperB", DEPLOYMENT_SAFE_NAMESPACE_TOPIC, "testKey")
                .withNewerValue(System.currentTimeMillis(), "initialValue");

        // WHEN
        // merge broken config
        HashMap<Object, Object> brokenConfig = new HashMap<Object, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                put("sleeperB", new HashMap<Object, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<Object, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "exit -1");
                    }});
                }});
            }});
        }};

        CountDownLatch sleeperBErrored = new CountDownLatch(1);
        CountDownLatch sleeperBRolledBack = new CountDownLatch(1);
        GlobalStateChangeListener listener = (service, oldState, newState) -> {
            if (service.getName().equals("sleeperB")) {
                if (newState.equals(State.ERRORED)) {
                    config.find(SERVICES_NAMESPACE_TOPIC, "sleeperB", DEPLOYMENT_SAFE_NAMESPACE_TOPIC, "testKey")
                            .withNewerValue(System.currentTimeMillis(), "setOnErrorValue");
                    sleeperBErrored.countDown();
                } else if (sleeperBErrored.getCount() == 0 && newState.equals(State.RUNNING)) {
                    // Rollback should only count after error
                    sleeperBRolledBack.countDown();
                }
            }
        };

        kernel.getContext().addGlobalStateChangeListener(listener);
        DeploymentResult result =
                deploymentConfigMerger.mergeInNewConfig(testRollbackDeploymentDocument(), brokenConfig)
                        .get(30, TimeUnit.SECONDS);

        // THEN
        // deployment should have errored and rolled back
        assertTrue(sleeperBErrored.await(1, TimeUnit.SECONDS));
        assertTrue(sleeperBRolledBack.await(1, TimeUnit.SECONDS));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());

        // Value set in listener should not have been rolled back
        assertEquals("setOnErrorValue",
                config.find(SERVICES_NAMESPACE_TOPIC, "sleeperB", DEPLOYMENT_SAFE_NAMESPACE_TOPIC, "testKey")
                        .getOnce());
        // remove listener
        kernel.getContext().removeGlobalStateChangeListener(listener);
    }

    private DeploymentDocument testDeploymentDocument() {
        return DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("id")
                .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).build();
    }

    private DeploymentDocument testRollbackDeploymentDocument() {
        return DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("rollback_id")
                .failureHandlingPolicy(FailureHandlingPolicy.ROLLBACK).build();
    }
}
