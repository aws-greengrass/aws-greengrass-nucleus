/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.event.Level;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityStatus;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesResponse;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvents;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.createServiceStateChangeWaiter;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.getNucleusConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;

@ExtendWith(GGExtension.class)
class DynamicComponentConfigurationValidationTest extends BaseITCase {
    private final static Logger log = LogManager.getLogger(DynamicComponentConfigurationValidationTest.class);
    private static final String DEFAULT_EXISTING_SERVICE_VERSION = "1.0.0";
    private static SocketOptions socketOptions;

    private Kernel kernel;
    private DeploymentConfigMerger deploymentConfigMerger;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        socketOptions = TestUtils.getSocketOptionsForIPC();
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);

        deploymentConfigMerger = kernel.getContext().get(DeploymentConfigMerger.class);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                DynamicComponentConfigurationValidationTest.class.getResource("onlyMain.yaml"));

        // launch kernel
        Runnable mainFinished = createServiceStateChangeWaiter(kernel, "main", 30, State.FINISHED);
        kernel.launch();
        mainFinished.run();

        // Start a new service
        AtomicBoolean mainRestarted = new AtomicBoolean(false);
        AtomicBoolean serviceStarted = new AtomicBoolean(false);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING) || newState.equals(State.FINISHED)) {
                mainRestarted.set(true);
            }
            if (service.getName().equals("OldService") && newState.equals(State.RUNNING) && oldState
                    .equals(State.STARTING)) {
                serviceStarted.set(true);
            }
        });

        List<String> serviceList = kernel.getMain().getDependencies().keySet().stream().map(GreengrassService::getName)
                .collect(Collectors.toList());
        serviceList.add("OldService");
        Map<String, Object> newConfig = new HashMap<String, Object>() {{
            put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                put("main", new HashMap<String, Object>() {{
                    put(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC, serviceList);
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                            kernel.getMain().getServiceConfig().lookupTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC)
                                    .toPOJO());
                }});
                put(DEFAULT_NUCLEUS_COMPONENT_NAME, getNucleusConfig(kernel));
                put("OldService", new HashMap<String, Object>() {{
                    put(CONFIGURATION_CONFIG_KEY, new HashMap<String, Object>() {{
                        put("ConfigKey1", "ConfigValue1");
                    }});
                    put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                        put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                    }});
                    put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                }});
            }});
        }};
        deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);

        assertTrue(mainRestarted.get());
        assertTrue(serviceStarted.get());
    }

    @AfterEach
    void after() throws IOException {
        if (kernel != null) {
            kernel.shutdown();
        }
        LogConfig.getRootLogConfig().reset();
    }

    @Test
    void GIVEN_deployment_changes_component_config_WHEN_component_validates_config_THEN_deployment_is_successful()
            throws Throwable {
        LogConfig.getRootLogConfig().setLevel(Level.DEBUG);
        // Subscribe to config validation on behalf of the running service
        CountDownLatch eventReceivedByClient = new CountDownLatch(1);
        Topics servicePrivateConfig = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC, "OldService",
                PRIVATE_STORE_NAMESPACE_TOPIC);
        String authToken = Coerce.toString(servicePrivateConfig.find(SERVICE_UNIQUE_ID_KEY));
        CountDownLatch subscriptionLatch = new CountDownLatch(1);
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel);
             AutoCloseable l = TestUtils.createCloseableLogListener(m -> {
                 if (m.getMessage().contains("Config IPC subscribe to config validation request")) {
                     subscriptionLatch.countDown();
                 }
             })) {

            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            SubscribeToValidateConfigurationUpdatesRequest subscribe = new SubscribeToValidateConfigurationUpdatesRequest();
            greengrassCoreIPCClient.subscribeToValidateConfigurationUpdates(subscribe, Optional.of(new StreamResponseHandler<ValidateConfigurationUpdateEvents>() {

                @Override
                public void onStreamEvent(ValidateConfigurationUpdateEvents events) {
                    assertNotNull(events);
                    assertNotNull(events.getValidateConfigurationUpdateEvent());
                    assertNotNull(events.getValidateConfigurationUpdateEvent().getConfiguration());
                    assertThat(events.getValidateConfigurationUpdateEvent().getConfiguration(),
                            IsMapContaining.hasEntry("ConfigKey1", "ConfigValue2"));
                    eventReceivedByClient.countDown();

                    SendConfigurationValidityReportRequest reportRequest =
                            new SendConfigurationValidityReportRequest();
                    ConfigurationValidityReport report = new ConfigurationValidityReport();
                    report.setStatus(ConfigurationValidityStatus.ACCEPTED);
                    report.setDeploymentId(events.getValidateConfigurationUpdateEvent().getDeploymentId());
                    reportRequest.setConfigurationValidityReport(report);

                    try {
                        greengrassCoreIPCClient.sendConfigurationValidityReport(reportRequest, Optional.empty()).getResponse()
                                .get(10, TimeUnit.SECONDS);
                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                        fail("received invalid update validate configuration event", e);
                    }
                }

                @Override
                public boolean onStreamError(Throwable error) {
                    log.atError().log("Received stream error.", error);
                    return false;
                }

                @Override
                public void onStreamClosed() {

                }
            }));
            assertTrue(subscriptionLatch.await(20, TimeUnit.SECONDS));

            // Attempt changing the configuration for the running service
            Map<String, Object> newConfig = new HashMap<String, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("main", kernel.getMain().getServiceConfig().toPOJO());
                    put("OldService", new HashMap<String, Object>() {{
                        put(CONFIGURATION_CONFIG_KEY, new HashMap<String, Object>() {{
                            put("ConfigKey1", "ConfigValue2");
                        }});
                        put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                            put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                        }});
                        put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                    }});
                    put(DEFAULT_NUCLEUS_COMPONENT_NAME, getNucleusConfig(kernel));
                }});
            }};
            DeploymentResult result =
                    deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);
            assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
            assertTrue(eventReceivedByClient.await(20, TimeUnit.SECONDS));
        }
    }

    @Test
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    void GIVEN_deployment_changes_component_config_WHEN_component_invalidates_config_THEN_deployment_fails()
            throws Throwable {
        // Subscribe to config validation on behalf of the running service
        CountDownLatch eventReceivedByClient = new CountDownLatch(1);
        Topics servicePrivateConfig = kernel.getConfig().findTopics(SERVICES_NAMESPACE_TOPIC, "OldService",
                PRIVATE_STORE_NAMESPACE_TOPIC);
        String authToken = Coerce.toString(servicePrivateConfig.find(SERVICE_UNIQUE_ID_KEY));
        try (EventStreamRPCConnection clientConnection =
                     IPCTestUtils.connectToGGCOverEventStreamIPC(socketOptions, authToken, kernel)) {
            GreengrassCoreIPCClient greengrassCoreIPCClient = new GreengrassCoreIPCClient(clientConnection);

            SubscribeToValidateConfigurationUpdatesRequest subscribe = new SubscribeToValidateConfigurationUpdatesRequest();
            CompletableFuture<SubscribeToValidateConfigurationUpdatesResponse> fut =
                    greengrassCoreIPCClient.subscribeToValidateConfigurationUpdates(subscribe,
                            Optional.of(new StreamResponseHandler<ValidateConfigurationUpdateEvents>() {
                                @Override
                                public void onStreamEvent(ValidateConfigurationUpdateEvents events) {
                                    assertNotNull(events);
                                    assertNotNull(events.getValidateConfigurationUpdateEvent());
                                    assertNotNull(events.getValidateConfigurationUpdateEvent().getConfiguration());
                                    assertThat(events.getValidateConfigurationUpdateEvent().getConfiguration(),
                                            IsMapContaining.hasEntry("ConfigKey1", "ConfigValue2"));
                                    eventReceivedByClient.countDown();

                                    SendConfigurationValidityReportRequest reportRequest =
                                            new SendConfigurationValidityReportRequest();
                                    ConfigurationValidityReport report = new ConfigurationValidityReport();
                                    report.setStatus(ConfigurationValidityStatus.REJECTED);
                                    report.setMessage("I don't like this configuration");
                                    report.setDeploymentId(events.getValidateConfigurationUpdateEvent().getDeploymentId());
                                    reportRequest.setConfigurationValidityReport(report);

                                    try {
                                        greengrassCoreIPCClient.sendConfigurationValidityReport(reportRequest, Optional.empty()).getResponse()
                                                .get(10, TimeUnit.SECONDS);
                                    } catch (InterruptedException | ExecutionException | TimeoutException e) {
                                        fail("received invalid update validate configuration event", e);
                                    }
                                }

                                @Override
                                public boolean onStreamError(Throwable error) {
                                    log.atError().log("Received stream error.", error);
                                    return false;
                                }

                                @Override
                                public void onStreamClosed() {

                                }
                            })).getResponse();

            try {
                fut.get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                fail("Caught exception when subscribing to configuration validation updates.");
            }

            // Attempt changing the configuration for the running service
            Map<String, Object> newConfig = new HashMap<String, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("main", kernel.getMain().getServiceConfig().toPOJO());
                    put("OldService", new HashMap<String, Object>() {{
                        put(CONFIGURATION_CONFIG_KEY, new HashMap<String, Object>() {{
                            put("ConfigKey1", "ConfigValue2");
                        }});
                        put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                            put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                        }});
                        put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                    }});
                    put(DEFAULT_NUCLEUS_COMPONENT_NAME, getNucleusConfig(kernel));
                }});
            }};
            DeploymentResult result =
                    deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);
            assertEquals(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, result.getDeploymentStatus());
            assertTrue(result.getFailureCause() instanceof ComponentConfigurationValidationException);
            assertTrue(result.getFailureCause().getMessage() != null && result.getFailureCause().getMessage().contains(
                    "Components reported that their to-be-deployed configuration is invalid { name = "
                            + "OldService, message = I don't like this configuration }"));
            assertTrue(eventReceivedByClient.await(500, TimeUnit.MILLISECONDS));
        }
    }

    private Deployment createTestDeployment() {
        DeploymentDocument doc = DeploymentDocument.builder().timestamp(System.currentTimeMillis()).deploymentId("id")
                .timestamp(System.currentTimeMillis() + 20).failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .componentUpdatePolicy(
                        new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS))
                .configurationValidationPolicy(DeploymentConfigurationValidationPolicy.builder()
                        .timeoutInSeconds(20).build())
                .build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }
}
