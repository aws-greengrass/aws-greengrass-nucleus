/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
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
import com.aws.greengrass.testcommons.testutilities.NoOpArtifactHandler;
import com.aws.greengrass.ipc.IPCClient;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityReport;
import software.amazon.awssdk.aws.greengrass.model.ConfigurationValidityStatus;
import software.amazon.awssdk.aws.greengrass.model.SendConfigurationValidityReportRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToValidateConfigurationUpdatesRequest;
import software.amazon.awssdk.aws.greengrass.model.ValidateConfigurationUpdateEvents;
import software.amazon.awssdk.crt.io.SocketOptions;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.ipc.AuthenticationHandler.SERVICE_UNIQUE_ID_KEY;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_RUN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.PRIVATE_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(GGExtension.class)
class DynamicComponentConfigurationValidationTest extends BaseITCase {
    private final static Logger log = LogManager.getLogger(DynamicComponentConfigurationValidationTest.class);
    private static final String DEFAULT_EXISTING_SERVICE_VERSION = "1.0.0";
    private static SocketOptions socketOptions;

    private IPCClient client;
    private Kernel kernel;
    private DeploymentConfigMerger deploymentConfigMerger;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionWithMessage(context, "Connection reset by peer");
        socketOptions = TestUtils.getSocketOptionsForIPC();
        kernel = new Kernel();
        NoOpArtifactHandler.register(kernel);
        deploymentConfigMerger = new DeploymentConfigMerger(kernel);
        kernel.parseArgs("-i",
                DynamicComponentConfigurationValidationTest.class.getResource("onlyMain.yaml").toString());

        // launch kernel
        CountDownLatch mainRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.FINISHED)) {
                mainRunning.countDown();
            }
        });
        kernel.launch();
        assertTrue(mainRunning.await(10, TimeUnit.SECONDS));

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
                put(DEFAULT_NUCLEUS_COMPONENT_NAME, kernel.findServiceTopic(DEFAULT_NUCLEUS_COMPONENT_NAME).toPOJO());
                put("OldService", new HashMap<String, Object>() {{
                    put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
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

        // Establish an IPC connection on behalf of the running service
        KernelIPCClientConfig config = getIPCConfigForService("OldService", kernel);
        client = new IPCClientImpl(config);
    }

    @AfterEach
    void after() throws IOException {
        if (client != null) {
            client.disconnect();
        }
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_deployment_changes_component_config_WHEN_component_validates_config_THEN_deployment_is_successful()
            throws Throwable {
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
                        put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                            put("ConfigKey1", "ConfigValue2");
                        }});
                        put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                            put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                        }});
                        put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                    }});
                }});
            }};
            DeploymentResult result =
                    deploymentConfigMerger.mergeInNewConfig(createTestDeployment(), newConfig).get(60, TimeUnit.SECONDS);
            assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
            assertTrue(eventReceivedByClient.await(20, TimeUnit.SECONDS));
        }
    }

    @Test
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
                    report.setStatus(ConfigurationValidityStatus.REJECTED);
                    report.setMessage("I don't like this configuration");
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

            // Attempt changing the configuration for the running service
            Map<String, Object> newConfig = new HashMap<String, Object>() {{
                put(SERVICES_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                    put("main", kernel.getMain().getServiceConfig().toPOJO());
                    put("OldService", new HashMap<String, Object>() {{
                        put(PARAMETERS_CONFIG_KEY, new HashMap<String, Object>() {{
                            put("ConfigKey1", "ConfigValue2");
                        }});
                        put(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, new HashMap<String, Object>() {{
                            put(LIFECYCLE_RUN_NAMESPACE_TOPIC, "echo Running OldService");
                        }});
                        put(VERSION_CONFIG_KEY, DEFAULT_EXISTING_SERVICE_VERSION);
                    }});
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
                        new ComponentUpdatePolicy(60, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS))
                .build();
        return new Deployment(doc, Deployment.DeploymentType.IOT_JOBS, "jobId", DEFAULT);
    }
}
