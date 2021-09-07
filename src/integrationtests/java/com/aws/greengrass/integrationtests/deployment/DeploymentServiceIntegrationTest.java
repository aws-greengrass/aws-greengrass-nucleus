/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentDocumentDownloader;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.convertFromDeploymentConfiguration;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.DEFAULT_IPC_API_TIMEOUT_SECONDS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SYSTEM_RESOURCE_LIMITS_TOPICS;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class DeploymentServiceIntegrationTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(DeploymentServiceIntegrationTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        kernel = new Kernel();
        mockRunasExePath();
        kernel.getContext().put(DeploymentDocumentDownloader.class, deploymentDocumentDownloader);
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                DeploymentServiceIntegrationTest.class.getResource("onlyMain.yaml"));

        // ensure deployment service starts
        CountDownLatch deploymentServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(DEPLOYMENT_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                deploymentServiceLatch.countDown();

            }
        });
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);

        kernel.launch();
        assertTrue(deploymentServiceLatch.await(10, TimeUnit.SECONDS));
        deploymentQueue =  kernel.getContext().get(DeploymentQueue.class);

        FleetStatusService fleetStatusService = (FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS);
        fleetStatusService.getIsConnected().set(false);
        // pre-load contents to package store
        localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_device_deployment_not_started_WHEN_new_deployment_THEN_first_deployment_cancelled() throws Exception {
        CountDownLatch cdlDeployNonDisruptable = new CountDownLatch(1);
        CountDownLatch cdlDeployRedSignal = new CountDownLatch(1);
        CountDownLatch cdlRedeployNonDisruptable = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {

            if (m.getMessage() != null) {
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId").equals("deployNonDisruptable")) {
                    cdlDeployNonDisruptable.countDown();
                }
                if (m.getMessage().contains("Discarding device deployment") && m.getContexts().get("DEPLOYMENT_ID").equals("deployRedSignal")) {
                    cdlDeployRedSignal.countDown();
                }
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId").equals("redeployNonDisruptable")) {
                    cdlRedeployNonDisruptable.countDown();
                }
            }
        };

        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            submitSampleJobDocument(
                    DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithNonDisruptableService.json").toURI(),
                    "deployNonDisruptable", DeploymentType.SHADOW);

            CountDownLatch nonDisruptableServiceServiceLatch = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if (service.getName().equals("NonDisruptableService") && newState.equals(State.RUNNING)) {
                    nonDisruptableServiceServiceLatch.countDown();

                }
            });
            assertTrue(nonDisruptableServiceServiceLatch.await(30, TimeUnit.SECONDS));

            try (EventStreamRPCConnection connection = IPCTestUtils.getEventStreamRpcConnection(kernel,
                    "NonDisruptableService")) {
                GreengrassCoreIPCClient ipcEventStreamClient = new GreengrassCoreIPCClient(connection);
                ipcEventStreamClient.subscribeToComponentUpdates(new SubscribeToComponentUpdatesRequest(),
                        Optional.of(new StreamResponseHandler<ComponentUpdatePolicyEvents>() {

                    @Override
                    public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                        if (streamEvent.getPreUpdateEvent() != null) {
                            try {
                                DeferComponentUpdateRequest deferComponentUpdateRequest = new DeferComponentUpdateRequest();
                                deferComponentUpdateRequest.setRecheckAfterMs(TimeUnit.SECONDS.toMillis(60));
                                deferComponentUpdateRequest.setMessage("Test");
                                ipcEventStreamClient.deferComponentUpdate(deferComponentUpdateRequest, Optional.empty())
                                        .getResponse().get(DEFAULT_IPC_API_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                            }
                        }
                    }

                    @Override
                    public boolean onStreamError(Throwable error) {
                        logger.atError().setCause(error).log("Caught error stream when subscribing for component " + "updates");
                        return false;
                    }

                    @Override
                    public void onStreamClosed() {

                    }
                }));

                assertTrue(cdlDeployNonDisruptable.await(30, TimeUnit.SECONDS));
                submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json")
                        .toURI(), "deployRedSignal", DeploymentType.SHADOW);
                submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithNonDisruptableService.json")
                        .toURI(), "redeployNonDisruptable", DeploymentType.SHADOW);
                assertTrue(cdlRedeployNonDisruptable.await(15, TimeUnit.SECONDS));
                assertTrue(cdlDeployRedSignal.await(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void GIVEN_a_cloud_deployment_WHEN_receives_deployment_THEN_service_runs_and_deployment_succeeds() throws Exception {
        CountDownLatch cdlDeployRedSignal = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {

            if (m.getMessage() != null) {
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId").equals("deployRedSignal")) {
                    cdlDeployRedSignal.countDown();
                }
            }
        };

        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {


            CountDownLatch redSignalServiceLatch = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if (service.getName().equals("RedSignal") && newState.equals(State.RUNNING)) {
                    redSignalServiceLatch.countDown();

                }
            });

            submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json")
                    .toURI(), "deployRedSignal", DeploymentType.SHADOW); // DeploymentType.SHADOW is used here and it
            // is same for DeploymentType.IOT_JOBS
            assertTrue(redSignalServiceLatch.await(30, TimeUnit.SECONDS));
            assertTrue(cdlDeployRedSignal.await(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void GIVEN_cloud_deployment_has_required_capabilities_WHEN_receives_deployment_THEN_fail_with_proper_detailed_status() throws Exception {
        CountDownLatch cdlDeployRedSignal = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null) {
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId").equals("deployRedSignal")) {
                    cdlDeployRedSignal.countDown();
                }
            }
        };

        CountDownLatch deploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.SHADOW, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("deployRedSignal") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("FAILED")) {
                deploymentCDL.countDown();
                assertThat(((Map) status.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME)).get(DEPLOYMENT_FAILURE_CAUSE_KEY),
                        equalTo("The current nucleus version doesn't support one or more capabilities that are required by "
                    + "this deployment: ANOTHER_CAPABILITY"));
            }
            return true;
        },"dummy");

        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRequiredCapability.json")
                    .toURI(), "deployRedSignal", DeploymentType.SHADOW);
            assertTrue(cdlDeployRedSignal.await(30, TimeUnit.SECONDS));
            assertTrue(deploymentCDL.await(10,TimeUnit.SECONDS));
        }
    }

    @Test
    void GIVEN_deployment_with_large_config_WHEN_receives_deployment_THEN_deployment_succeeds() throws Exception {
        CountDownLatch deploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("ComponentConfig") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                deploymentCDL.countDown();
            }
            return true;
        }, "dummy");

        Configuration deployedConfiguration = OBJECT_MAPPER.readValue(new File(DeploymentServiceIntegrationTest.class
                .getResource("FleetConfigWithComponentConfigTestService.json").toURI()), Configuration.class);
        deployedConfiguration.setCreationTimestamp(System.currentTimeMillis());
        deployedConfiguration.setConfigurationArn("ComponentConfig");
        DeploymentDocument configurationDownloadedUsingDataPlaneAPI = convertFromDeploymentConfiguration(deployedConfiguration);
        // remove the configuration update section from deployedConfiguration after configurationDownloadedUsingDataPlaneAPI is created
        deployedConfiguration.getComponents().values().forEach( componentUpdate -> componentUpdate.setConfigurationUpdate(null));

        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(deployedConfiguration), DeploymentType.IOT_JOBS, deployedConfiguration.getConfigurationArn());

        when(deploymentDocumentDownloader.download(any())).thenReturn(configurationDownloadedUsingDataPlaneAPI);
        deploymentQueue.offer(deployment);

        assertTrue(deploymentCDL.await(10,TimeUnit.SECONDS));
        Map<String, Object> resultConfig =
                kernel.findServiceTopic("aws.iot.gg.test.integ.ComponentConfigTestService")
                        .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();

        assertThat(resultConfig, IsMapContaining.hasEntry("singleLevelKey", "updated value of singleLevelKey"));
        assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Collections.singletonList("item3")));
        assertThat(resultConfig, IsMapContaining.hasEntry("emptyStringKey", ""));
        assertThat(resultConfig, IsMapContaining.hasEntry("emptyListKey", Collections.emptyList()));
        assertThat(resultConfig, IsMapContaining.hasEntry("emptyObjectKey", Collections.emptyMap()));
        assertThat(resultConfig, IsMapContaining.hasEntry("defaultIsNullKey", "updated value of defaultIsNullKey"));
        assertThat(resultConfig, IsMapContaining.hasEntry("willBeNullKey", null));
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void GIVEN_deployment_with_system_resource_WHEN_receives_deployment_THEN_deployment_succeeds() throws Exception {
        CountDownLatch deploymentFinished = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null) {
                if (m.getMessage().contains("Current deployment finished") && m.getContexts().get("DeploymentId")
                        .equals("deployComponentWithResourceLimits")) {
                    deploymentFinished.countDown();
                }
            }
        };

        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            CountDownLatch componentRunning = new CountDownLatch(1);
            kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
                if (service.getName().equals("RedSignal") && newState.equals(State.RUNNING)) {
                    componentRunning.countDown();

                }
            });

            submitSampleJobDocument(
                    DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithResourceLimits.json").toURI(),
                    "deployComponentWithResourceLimits", DeploymentType.SHADOW);
            assertTrue(componentRunning.await(30, TimeUnit.SECONDS));
            assertTrue(deploymentFinished.await(30, TimeUnit.SECONDS));

            long memory = Coerce.toLong(kernel.findServiceTopic("RedSignal")
                    .find(RUN_WITH_NAMESPACE_TOPIC, SYSTEM_RESOURCE_LIMITS_TOPICS, "memory"));
            assertEquals(1024000, memory);
            double cpus = Coerce.toDouble(kernel.findServiceTopic("RedSignal")
                    .find(RUN_WITH_NAMESPACE_TOPIC, SYSTEM_RESOURCE_LIMITS_TOPICS, "cpus"));
            assertEquals(1.5, cpus);
        }
    }

    @Test
    void WHEN_multiple_local_deployment_scheduled_THEN_all_deployments_succeed() throws Exception {

        CountDownLatch firstDeploymentCDL = new CountDownLatch(1);
        CountDownLatch secondDeploymentCDL = new CountDownLatch(1);
        CountDownLatch thirdDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {

            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                firstDeploymentCDL.countDown();
            }

            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                secondDeploymentCDL.countDown();
            }

            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("thirdDeployment") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")){
                thirdDeploymentCDL.countDown();
            }
            return true;
        },"DeploymentServiceIntegrationTest" );

        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("YellowSignal", "1.0.0");
        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("firstDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        componentsToMerge = new HashMap<>();
        componentsToMerge.put("SimpleApp", "1.0.0");
        request = LocalOverrideRequest.builder().requestId("secondDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        componentsToMerge = new HashMap<>();
        componentsToMerge.put("SomeService", "1.0.0");
        request = LocalOverrideRequest.builder().requestId("thirdDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        assertTrue(firstDeploymentCDL.await(10, TimeUnit.SECONDS), "First deployment did not succeed");
        assertTrue(secondDeploymentCDL.await(10, TimeUnit.SECONDS), "Second deployment did not succeed");
        assertTrue(thirdDeploymentCDL.await(10, TimeUnit.SECONDS), "Third deployment did not succeed");
    }

    @Test
    void GIVEN_local_deployment_WHEN_component_has_circular_dependency_THEN_deployments_fails_with_appropriate_error(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, ExecutionException.class);
        CountDownLatch firstErroredCDL = new CountDownLatch(1);
        String recipeDir = localStoreContentPath.resolve("recipes").toAbsolutePath().toString();
        String artifactsDir = localStoreContentPath.resolve("artifacts").toAbsolutePath().toString();

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("ComponentWithCircularDependency", "1.0.0");
        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("firstDeployment")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .recipeDirectoryPath(recipeDir).artifactsDirectoryPath(artifactsDir).build();

        submitLocalDocument(request);

        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {

            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstDeployment")
                    && status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("FAILED")) {
                Map<String, String> detailedStatus = (Map<String, String>) status.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME);
                if (detailedStatus.get("deployment-failure-cause").contains("Circular dependency detected")
                        && detailedStatus.get("detailed-deployment-status").equals("FAILED_NO_STATE_CHANGE")) {
                    firstErroredCDL.countDown();
                }
            }
            return true;
        }, "DeploymentServiceIntegrationTest2");

        firstErroredCDL.await(10, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_local_deployment_WHEN_required_capabilities_not_present_THEN_deployments_fails_with_appropriate_error() throws Exception {
        CountDownLatch deploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.LOCAL, (status) -> {

            if(status.get(DEPLOYMENT_ID_KEY_NAME).equals("requiredCapabilityNotPresent") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("FAILED")) {
                deploymentCDL.countDown();
                assertThat(((Map)status.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME)).get(DEPLOYMENT_FAILURE_CAUSE_KEY),
                        equalTo("The current nucleus version doesn't support one or more capabilities that are "
                        + "required by this deployment: NOT_SUPPORTED_1, NOT_SUPPORTED_2"));
            }

            return true;
        },"DeploymentServiceIntegrationTest3" );

        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("YellowSignal", "1.0.0");
        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("requiredCapabilityNotPresent")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .requiredCapabilities(Arrays.asList("NOT_SUPPORTED_1", "NOT_SUPPORTED_2", "LARGE_CONFIGURATION"))
                .build();

        submitLocalDocument(request);
        assertTrue(deploymentCDL.await(10, TimeUnit.SECONDS), "Deployment should fail with "
                + "requiredCapabilityNotPresent.");
    }

    private void submitSampleJobDocument(URI uri, String arn, DeploymentType type) throws Exception {
        Configuration deploymentConfiguration = OBJECT_MAPPER.readValue(new File(uri), Configuration.class);
        deploymentConfiguration.setCreationTimestamp(System.currentTimeMillis());
        deploymentConfiguration.setConfigurationArn(arn);
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(deploymentConfiguration), type, deploymentConfiguration.getConfigurationArn());
        deploymentQueue.offer(deployment);
    }

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }
}
