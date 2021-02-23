/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.Deployment;
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
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.DEFAULT_IPC_API_TIMEOUT_SECONDS;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
class DeploymentServiceIntegrationTest extends BaseITCase {
    private static final Logger logger = LogManager.getLogger(DeploymentServiceIntegrationTest.class);
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        kernel = new Kernel();
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

        firstDeploymentCDL.await(10, TimeUnit.SECONDS);
        secondDeploymentCDL.await(10, TimeUnit.SECONDS);
        thirdDeploymentCDL.await(10, TimeUnit.SECONDS);
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
                if (detailedStatus.get("deployment-failure-cause").equals("Circular dependency detected for Component ComponentWithCircularDependency")
                        && detailedStatus.get("detailed-deployment-status").equals("FAILED_NO_STATE_CHANGE")) {
                    firstErroredCDL.countDown();
                }
            }
            return true;
        }, "DeploymentServiceIntegrationTest2");

        firstErroredCDL.await(10, TimeUnit.SECONDS);
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
