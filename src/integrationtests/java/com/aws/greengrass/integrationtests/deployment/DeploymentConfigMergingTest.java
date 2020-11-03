/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.lifecyclemanager.GenericExternalService;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesResponse;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus.SUCCESSFUL;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SETENV_CONFIG_NAMESPACE;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(GGExtension.class)
class DeploymentConfigMergingTest extends BaseITCase {
    private Kernel kernel;
    private DeploymentConfigMerger deploymentConfigMerger;
    private static SocketOptions socketOptions;
    private static Logger logger = LogManager.getLogger(DeploymentConfigMergingTest.class);

    @BeforeAll
    static void initialize() {
        socketOptions = TestUtils.getSocketOptionsForIPC();
    }

    @BeforeEach
    void before(TestInfo testInfo) {
        kernel = new Kernel();
        deploymentConfigMerger = new DeploymentConfigMerger(kernel);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @AfterAll
    static void tearDown() {
        if (socketOptions != null) {
            socketOptions.close();
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
        deploymentConfigMerger.mergeInNewConfig(testDeployment(),
                (Map<String, Object>) JSON.std.with(new YAMLFactory()).anyFrom(getClass().getResource("delta.yaml")))
                .get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(mainRestarted.await(10, TimeUnit.SECONDS));
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

        AtomicBoolean safeUpdateRegistered = new AtomicBoolean();
        Consumer<GreengrassLogMessage> listener = (m) -> {
            if ("register-service-update-action".equals(m.getEventType())) {
                safeUpdateRegistered.set(true);
            }
        };
        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            kernel.launch();
            assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

            // WHEN
            CountDownLatch mainRestarted = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if (service.getName().equals("main") && newState.equals(State.FINISHED) && oldState.equals(State.STARTING)) {
                    mainRestarted.countDown();
                }
            });

            deploymentConfigMerger.mergeInNewConfig(testDeployment(), new HashMap<String, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("main", new HashMap<String, Object>() {{
                        put(SETENV_CONFIG_NAMESPACE, new HashMap<String, Object>() {{
                            put("HELLO", "redefined");
                        }});
                    }});
                }});
            }}).get(60, TimeUnit.SECONDS);

            // THEN
            assertTrue(mainRestarted.await(10, TimeUnit.SECONDS));
            assertEquals("redefined", kernel.findServiceTopic("main").find(SETENV_CONFIG_NAMESPACE, "HELLO").getOnce());
            assertTrue(safeUpdateRegistered.get());
        }
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
        AtomicBoolean mainRestarted = new AtomicBoolean(false);
        AtomicBoolean newServiceStarted = new AtomicBoolean(false);

        // Check that new_service starts and then main gets restarted
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("new_service") && newState.equals(State.RUNNING)) {
                newServiceStarted.set(true);
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.get() && service.getName().equals("main") && newState.equals(State.FINISHED)
                    && oldState.equals(State.STARTING)) {
                mainRestarted.set(true);
            }
        });
        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(GreengrassService::getName)
                .collect(Collectors.toList());
        serviceList.add("new_service");
        deploymentConfigMerger.mergeInNewConfig(testDeployment(), new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, serviceList);
                }});

                put("new_service", new HashMap<String, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                            put("script", "echo done");
                        }});
                    }});
                }});

                put(DEFAULT_NUCLEUS_COMPONENT_NAME, getNucleusConfig());
            }});
        }}).get(60, TimeUnit.SECONDS);
        // THEN
        assertTrue(newServiceStarted.get());
    }

    private Map<String, Object> getNucleusConfig() {
        Optional<GreengrassService> nucleus =
                kernel.getMain().getDependencies().keySet().stream().filter(s ->
                        DEFAULT_NUCLEUS_COMPONENT_NAME.equalsIgnoreCase(s.getServiceName()))
                        .findFirst();
        assertTrue(nucleus.isPresent(), "no nucleus config available");
        return nucleus.get().getConfig().toPOJO();
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
        AtomicBoolean newService2Started = new AtomicBoolean(false);
        AtomicBoolean newServiceStarted = new AtomicBoolean(false);

        // Check that new_service2 starts, then new_service, and then main gets restarted
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("new_service2") && newState.equals(State.RUNNING)) {
                newService2Started.set(true);
            }
            if (newService2Started.get() && service.getName().equals("new_service") && newState
                    .equals(State.RUNNING)) {
                newServiceStarted.set(true);
            }
            // Only count main as started if its dependency (new_service) has already been started
            if (newServiceStarted.get()  && service.getName().equals("main") && newState.equals(State.FINISHED)
                    && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(GreengrassService::getName)
                .collect(Collectors.toList());
        serviceList.add("new_service");

        deploymentConfigMerger.mergeInNewConfig(testDeployment(), new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, serviceList);
                }});

                put("new_service", new HashMap<String, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo done");
                    }});
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("new_service2"));
                }});

                put("new_service2", new HashMap<String, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo done");
                    }});
                }});

                put(DEFAULT_NUCLEUS_COMPONENT_NAME, getNucleusConfig());
            }});
        }}).get(60, TimeUnit.SECONDS);

        // THEN
        assertTrue(newService2Started.get());
        assertTrue(newServiceStarted.get());
        assertTrue(mainRestarted.await(10, TimeUnit.SECONDS));
        assertThat(kernel.orderedDependencies().stream().map(GreengrassService::getName).collect(Collectors.toList()),
                containsInRelativeOrder("new_service2", "new_service", "main"));
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_merge_same_doc_happens_twice_THEN_second_merge_should_not_restart_services()
            throws Throwable {

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("single_service.yaml").toString());

        // launch kernel
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();

        assertTrue(mainRunning.await(5, TimeUnit.SECONDS));

        Map<String, Object> nucleusConfig = getNucleusConfig();
        HashMap<String, Object> newConfig = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC,
                            Arrays.asList("new_service", DEFAULT_NUCLEUS_COMPONENT_NAME));
                }});

                put("new_service", new HashMap<String, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo done");
                    }});
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, Arrays.asList("new_service2"));
                }});

                put("new_service2", new HashMap<String, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo done");
                    }});
                }});

                put(DEFAULT_NUCLEUS_COMPONENT_NAME, nucleusConfig);
            }});
        }};

        // do first merge
        CountDownLatch mainRestarted = new CountDownLatch(1);
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
            if (newServiceStarted.get() && service.getName().equals("main") && newState.equals(State.FINISHED)
                    && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        };
        kernel.getContext().addGlobalStateChangeListener(listener);

        GreengrassService main = kernel.locate("main");
        deploymentConfigMerger.mergeInNewConfig(testDeployment(), newConfig).get(60, TimeUnit.SECONDS);

        // Verify that first merge succeeded.
        assertTrue(newService2Started.get());
        assertTrue(newServiceStarted.get());
        assertTrue(mainRestarted.await(10, TimeUnit.SECONDS));
        assertThat(kernel.orderedDependencies().stream().map(GreengrassService::getName).collect(Collectors.toList()),
                containsInRelativeOrder("new_service2", "new_service", "main"));
        // Wait for main to finish before continuing, otherwise the state change listner may cause a failure
        assertThat(main::getState, eventuallyEval(is(State.FINISHED)));

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
        deploymentConfigMerger.mergeInNewConfig(testDeployment(), newConfig).get(60, TimeUnit.SECONDS);

        // main should be finished
        assertEquals(State.FINISHED, main.getState());

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
            if (Coerce.toEnum(State.class, t).isRunning()) {
                mainRunningLatch.countDown();
            }
        });

        //wait for main to run
        assertTrue(mainRunningLatch.await(60, TimeUnit.SECONDS), "main running");

        Map<String, Object> currentConfig = new HashMap<>(kernel.getConfig().toPOJO());

        Map<String, Map> servicesConfig = (Map<String, Map>) currentConfig.get(SERVICES_NAMESPACE_TOPIC);

        //removing all services in the current kernel config except sleeperB, main, and nucleus
        servicesConfig.keySet().removeIf(serviceName -> !"sleeperB".equals(serviceName)
                && !"main".equals(serviceName)
                && !DEFAULT_NUCLEUS_COMPONENT_NAME.equalsIgnoreCase(serviceName));
        List<String> dependencies =
                new ArrayList<>((List<String>) servicesConfig.get("main").get(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC));
        //removing main's dependency on sleeperA, Now sleeperA is an unused dependency
        dependencies.removeIf(s -> s.contains("sleeperA"));
        servicesConfig.get("main").put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, dependencies);
        // updating service B's run
        Map lifecycle = (Map) servicesConfig.get("sleeperB").get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        lifecycle.put(LIFECYCLE_RUN_NAMESPACE_TOPIC,
                ((String) lifecycle.get(LIFECYCLE_RUN_NAMESPACE_TOPIC)).replace("5", "10"));

        Future<DeploymentResult> deploymentFuture = deploymentConfigMerger.mergeInNewConfig(testDeployment(), currentConfig);

        DeploymentResult deploymentResult = deploymentFuture.get(30, TimeUnit.SECONDS);

        assertEquals(SUCCESSFUL, deploymentResult.getDeploymentStatus());
        GreengrassService main = kernel.locate("main");
        assertThat(main::getState, eventuallyEval(is(State.RUNNING)));
        GreengrassService sleeperB = kernel.locate("sleeperB");
        assertEquals(State.RUNNING, sleeperB.getState());
        // ensure context finish all tasks
        kernel.getContext().waitForPublishQueueToClear();
        // ensuring config value for sleeperA is removed
        assertFalse(kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC).children.containsKey("sleeperA"),
                "sleeperA removed");
        // ensure kernel no longer holds a reference of sleeperA
        assertThrows(ServiceLoadException.class, () -> kernel.locate("sleeperA"));

        List<String> orderedDependencies = kernel.orderedDependencies().stream()
                .filter(greengrassService -> greengrassService instanceof GenericExternalService)
                .map(GreengrassService::getName).collect(Collectors.toList());

        assertThat(orderedDependencies, containsInAnyOrder("sleeperB", DEFAULT_NUCLEUS_COMPONENT_NAME, "main"));
        // sleeperB and nucleus can be in any order, but must be before main
        assertThat(orderedDependencies, containsInRelativeOrder("sleeperB", "main"));
        assertThat(orderedDependencies, containsInRelativeOrder(DEFAULT_NUCLEUS_COMPONENT_NAME, "main"));
    }

    @Test
    @SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
    void GIVEN_a_running_service_is_not_disruptable_WHEN_deployed_THEN_deployment_waits() throws Throwable {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("non_disruptable_service.yaml").toString());
        kernel.launch();

        CountDownLatch mainFinished = new CountDownLatch(1);
        kernel.getMain().addStateSubscriber((WhatHappened what, Topic t) -> {
            if (Coerce.toEnum(State.class, t).equals(State.FINISHED)) {
                mainFinished.countDown();
            }
        });
        // wait for main to finish
        assertTrue(mainFinished.await(10, TimeUnit.SECONDS));

        AtomicInteger deferCount = new AtomicInteger(0);
        AtomicInteger preComponentUpdateCount = new AtomicInteger(0);
        CountDownLatch postComponentUpdateRecieved = new CountDownLatch(1);
        String authToken = IPCTestUtils.getAuthTokeForService(kernel, "nondisruptable");
        final EventStreamRPCConnection clientConnection = IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions,
                authToken,
                kernel);
        GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);
        SubscribeToComponentUpdatesRequest subscribeToComponentUpdatesRequest = new SubscribeToComponentUpdatesRequest();
        CompletableFuture<SubscribeToComponentUpdatesResponse> fut =
        greengrassCoreIPCClient.subscribeToComponentUpdates(subscribeToComponentUpdatesRequest,
                Optional.of(new StreamResponseHandler<ComponentUpdatePolicyEvents>() {
                    @Override
                    public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                        if (streamEvent.getPreUpdateEvent() != null) {
                            preComponentUpdateCount.getAndIncrement();
                            if (deferCount.get() < 1) {
                                DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
                                deferComponentUpdateRequest.setRecheckAfterMs(Duration.ofSeconds(7).toMillis());
                                deferComponentUpdateRequest.setMessage("Test");
                                greengrassCoreIPCClient.deferComponentUpdate(deferComponentUpdateRequest,
                                        Optional.empty());
                                deferCount.getAndIncrement();
                            }
                        }
                        if (streamEvent.getPostUpdateEvent() != null) {
                                postComponentUpdateRecieved.countDown();
                                clientConnection.disconnect();
                        }
                    }

                    @Override
                    public boolean onStreamError(Throwable error) {
                        logger.atError().setCause(error).log("Caught an error on the stream");
                        return false;
                    }

                    @Override
                    public void onStreamClosed() {
                        logger.atWarn().log("Stream closed by the server");
                    }
                }
        )).getResponse();
        try {
            fut.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.atError().setCause(e).log("Error when subscribing to component updates");
            fail("Caught exception when subscribing to component updates");
        }

        Map<String, Object> currentConfig = new HashMap<>(kernel.getConfig().toPOJO());
        Future<DeploymentResult> future =
                deploymentConfigMerger.mergeInNewConfig(testDeployment(), currentConfig);

        // update should be deferred for 5 seconds
        assertThrows(TimeoutException.class, () -> future.get(5, TimeUnit.SECONDS),
                "Merge should not happen within 5 seconds");

        assertTrue(postComponentUpdateRecieved.await(15,TimeUnit.SECONDS));
        assertEquals(2, preComponentUpdateCount.get());
    }

    @Test
    void GIVEN_service_running_with_rollback_safe_param_WHEN_rollback_THEN_rollback_safe_param_not_updated(
            ExtensionContext context) throws Throwable {

        ignoreExceptionUltimateCauseWithMessage(context, "Service sleeperB in broken state after deployment");

        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("short_running_services_using_startup_script.yaml")
                .toString());

        kernel.launch();

        Configuration config = kernel.getConfig();
        config.lookup(SERVICES_NAMESPACE_TOPIC, "sleeperB", RUNTIME_STORE_NAMESPACE_TOPIC, "testKey")
                .withNewerValue(System.currentTimeMillis(), "initialValue");

        // WHEN
        // merge broken config
        HashMap<String, Object> brokenConfig = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("sleeperB", new HashMap<String, Object>() {{
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_STARTUP_NAMESPACE_TOPIC, "exit 1");
                    }});
                }});

                put(DEFAULT_NUCLEUS_COMPONENT_NAME, getNucleusConfig());
            }});
        }};

        AtomicBoolean sleeperBBroken = new AtomicBoolean(false);
        CountDownLatch sleeperBRolledBack = new CountDownLatch(1);
        GlobalStateChangeListener listener = (service, oldState, newState) -> {
            if (service.getName().equals("sleeperB")) {
                if (newState.equals(State.ERRORED)) {
                    config.find(SERVICES_NAMESPACE_TOPIC, "sleeperB", RUNTIME_STORE_NAMESPACE_TOPIC, "testKey")
                            .withNewerValue(System.currentTimeMillis(), "setOnErrorValue");
                }
                if (newState.equals(State.BROKEN)) {
                    sleeperBBroken.set(true);
                }
                if (sleeperBBroken.get() && newState.equals(State.RUNNING)) {
                    // Rollback should only count after error
                    sleeperBRolledBack.countDown();
                }
            }
        };

        kernel.getContext().get(DeploymentDirectoryManager.class).createNewDeploymentDirectory(
                "mockFleetConfigArn");
        kernel.getContext().addGlobalStateChangeListener(listener);
        DeploymentResult result =
                deploymentConfigMerger.mergeInNewConfig(testRollbackDeployment(), brokenConfig)
                        .get(40, TimeUnit.SECONDS);

        // THEN
        // deployment should have errored and rolled back
        assertTrue(sleeperBRolledBack.await(10, TimeUnit.SECONDS));
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE, result.getDeploymentStatus());

        // Value set in listener should not have been rolled back
        assertEquals("setOnErrorValue",
                config.find(SERVICES_NAMESPACE_TOPIC, "sleeperB", RUNTIME_STORE_NAMESPACE_TOPIC, "testKey")
                        .getOnce());
        // remove listener
        kernel.getContext().removeGlobalStateChangeListener(listener);
    }

    @Test
    void GIVEN_kernel_running_single_service_WHEN_deployment_with_skip_safety_check_config_THEN_merge_without_checking_safety()
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
            if (service.getName().equals("main") && newState.equals(State.FINISHED) && oldState.equals(State.STARTING)) {
                mainRestarted.countDown();
            }
        });
        AtomicBoolean safeUpdateSkipped= new AtomicBoolean();
        Consumer<GreengrassLogMessage> listener = (m) -> {
            if ("Deployment is configured to skip safety check, not waiting for safe time to update"
                    .equals(m.getMessage())) {
                    safeUpdateSkipped.set(true);
                }
        };
        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            deploymentConfigMerger.mergeInNewConfig(testDeploymentWithSkipSafetyCheckConfig(), new HashMap<String, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("main", new HashMap<String, Object>() {{
                        put(SETENV_CONFIG_NAMESPACE, new HashMap<String, Object>() {{
                            put("HELLO", "redefined");
                        }});
                    }});
                }});
            }}).get(60, TimeUnit.SECONDS);

            // THEN
            assertTrue(mainRestarted.await(10, TimeUnit.SECONDS));
            assertEquals("redefined", kernel.findServiceTopic("main")
                    .find(SETENV_CONFIG_NAMESPACE, "HELLO").getOnce());
            assertTrue(safeUpdateSkipped.get());
        }
    }

    private Deployment testDeployment() {
        DeploymentDocument doc = DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("id")
                .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .componentUpdatePolicy(
                        new ComponentUpdatePolicy(3, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS))
                .build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }

    private Deployment testRollbackDeployment() {
        DeploymentDocument doc = DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                .deploymentId("rollback_id")
                .failureHandlingPolicy(FailureHandlingPolicy.ROLLBACK)
                .componentUpdatePolicy(
                        new ComponentUpdatePolicy(60, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS))
                .build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }

    private Deployment testDeploymentWithSkipSafetyCheckConfig() {
        DeploymentDocument doc = DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("id")
                .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .componentUpdatePolicy(
                        new ComponentUpdatePolicy(60, ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS))
                .build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }
}
