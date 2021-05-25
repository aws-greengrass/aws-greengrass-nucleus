/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.status;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.IotJobsClientWrapper;
import com.aws.greengrass.deployment.IotJobsHelper;
import com.aws.greengrass.deployment.ThingGroupHelper;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.FleetStatusDetails;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.status.OverallStatus;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionResponse;

import java.io.EOFException;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.IotJobsHelper.UPDATE_DEPLOYMENT_STATUS_ACCEPTED;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class IotJobsFleetStatusServiceTest extends BaseITCase {

    private static final String MOCK_FLEET_CONFIG_ARN =
            "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static DeviceConfiguration deviceConfiguration;
    private static DeploymentService deploymentService;
    private static Kernel kernel;
    private final Set<String> componentNamesToCheck = new HashSet<>();
    private Consumer<GreengrassLogMessage> logListener;
    @Mock
    private MqttClient mqttClient;
    @Mock
    private IotJobsClientWrapper mockIotJobsClientWrapper;
    @Mock
    private ThingGroupHelper thingGroupHelper;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;

    @Captor
    private ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> jobsAcceptedHandlerCaptor;

    @BeforeEach
    void setupKernel(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, TLSAuthException.class);
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionUltimateCauseOfType(context, EOFException.class);

        CountDownLatch fssRunning = new CountDownLatch(1);
        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        CompletableFuture cf = new CompletableFuture();
        cf.complete(null);
        lenient().when(mockIotJobsClientWrapper.PublishUpdateJobExecution(any(UpdateJobExecutionRequest.class),
                any(QualityOfService.class))).thenAnswer(invocationOnMock -> {
            verify(mockIotJobsClientWrapper, atLeastOnce()).SubscribeToUpdateJobExecutionAccepted(any(),
                    eq(QualityOfService.AT_LEAST_ONCE), jobsAcceptedHandlerCaptor.capture());
            Consumer<UpdateJobExecutionResponse> jobResponseConsumer = jobsAcceptedHandlerCaptor.getValue();
            UpdateJobExecutionResponse mockJobExecutionResponse = mock(UpdateJobExecutionResponse.class);
            jobResponseConsumer.accept(mockJobExecutionResponse);
            return cf;
        });
        kernel = new Kernel();

        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                IotJobsFleetStatusServiceTest.class.getResource("onlyMain.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(ThingGroupHelper.class, thingGroupHelper);

        componentNamesToCheck.clear();
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)
                    && newState.equals(State.RUNNING)) {
                fssRunning.countDown();
            }
            if (service.getName().equals(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)
                    && newState.equals(State.RUNNING)) {
                deploymentServiceRunning.countDown();
                deploymentService = (DeploymentService) service;
                IotJobsHelper iotJobsHelper = deploymentService.getContext().get(IotJobsHelper.class);
                iotJobsHelper.setIotJobsClientWrapper(mockIotJobsClientWrapper);
            }
            componentNamesToCheck.add(service.getName());
        });
        // set required instances from context
        deviceConfiguration =
                new DeviceConfiguration(kernel, "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com", "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath",
                        "certFilePath", "caFilePath", "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        // pre-load contents to package store
        Path localStoreContentPath =
                Paths.get(IotJobsFleetStatusServiceTest.class.getResource("local_store_content").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"), kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(), REPLACE_EXISTING);
        kernel.launch();
        assertTrue(fssRunning.await(10, TimeUnit.SECONDS));
        assertTrue(deploymentServiceRunning.await(10, TimeUnit.SECONDS));
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_jobs_deployment_WHEN_deployment_finishes_THEN_status_is_uploaded_to_cloud(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, InvocationTargetException.class);
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get()).put("plugin",
                GreengrassService.class.getName());
        assertNotNull(deviceConfiguration.getThingName());
        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getEventType() != null && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().equals("Status update published to FSS")) {
                fssPublishLatch.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);

        offerSampleIoTJobsDeployment("FleetStatusServiceConfig.json", TEST_JOB_ID_1);
        assertTrue(fssPublishLatch.await(60, TimeUnit.SECONDS));
        verify(mqttClient, atLeastOnce()).publish(captor.capture());

        List<PublishRequest> prs = captor.getAllValues();
        // Get the last FSS publish request which should have all the components information.
        PublishRequest pr = prs.get(prs.size() - 1);
        try {
            FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(pr.getPayload(),
                    FleetStatusDetails.class);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size());
            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                componentNamesToCheck.remove(componentStatusDetails.getComponentName());
                if (componentStatusDetails.getComponentName().equals("CustomerApp")) {
                    assertEquals("1.0.0", componentStatusDetails.getVersion());
                    assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
                    assertEquals(MOCK_FLEET_CONFIG_ARN, componentStatusDetails.getFleetConfigArns().get(0));
                    assertEquals(State.FINISHED, componentStatusDetails.getState());
                    assertTrue(componentStatusDetails.isRoot());
                } else if (componentStatusDetails.getComponentName().equals("Mosquitto")) {
                    assertEquals("1.0.0", componentStatusDetails.getVersion());
                    assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
                    assertEquals(MOCK_FLEET_CONFIG_ARN, componentStatusDetails.getFleetConfigArns().get(0));
                    assertEquals(State.RUNNING, componentStatusDetails.getState());
                    assertFalse(componentStatusDetails.isRoot());
                } else {
                    assertFalse(componentStatusDetails.isRoot());
                }
            });
        } catch (UnrecognizedPropertyException ignored) {
        }
        assertEquals(0, componentNamesToCheck.size());
        Slf4jLogAdapter.removeGlobalListener(logListener);
    }

    @Test
    void WHEN_deployment_bumps_up_component_version_THEN_status_of_new_version_is_updated_to_cloud() throws Exception {
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get()).put("plugin",
                GreengrassService.class.getName());
        assertNotNull(deviceConfiguration.getThingName());
        CountDownLatch jobsDeploymentLatch = new CountDownLatch(1);
        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getMessage() != null && eslm.getMessage().equals(UPDATE_DEPLOYMENT_STATUS_ACCEPTED)
                    && eslm.getContexts().get("JobId").equals("simpleApp2")) {
                jobsDeploymentLatch.countDown();
            }
            if (jobsDeploymentLatch.getCount() == 0 && eslm.getEventType() != null
                    && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().equals("Status update published to FSS")) {
                fssPublishLatch.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);
        // First local deployment adds SimpleApp v1
        Map<String, String> componentsToMerge = new HashMap<>();
        componentsToMerge.put("SimpleApp", "1.0.0");
        LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("SimpleApp1")
                .componentsToMerge(componentsToMerge)
                .requestTimestamp(System.currentTimeMillis())
                .build();
        submitLocalDocument(request);
        // Second local deployment removes SimpleApp v1
        request = LocalOverrideRequest.builder().requestId("removeSimpleApp")
                .componentsToRemove(Arrays.asList("SimpleApp"))
                .requestTimestamp(System.currentTimeMillis())
                .build();
        submitLocalDocument(request);
        // Cloud deployment adds SimpleApp v2. First two deployments are local because this edge case is hit when device is
        // offline after receiving the deployment and cannot emit FSS update. Since local deployment do not emit FSS update,
        // this test simulates the device being offline by using local deployments.
        offerSampleIoTJobsDeployment("FleetConfigSimpleApp2.json", "simpleApp2");
        assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
        verify(mqttClient, atLeastOnce()).publish(captor.capture());

        List<PublishRequest> prs = captor.getAllValues();
        // Get the last FSS publish request which should have component info of simpleApp v2 and other built in services
        PublishRequest pr = prs.get(prs.size() - 1);
        try {
            FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(pr.getPayload(),
                    FleetStatusDetails.class);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size());
            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                componentNamesToCheck.remove(componentStatusDetails.getComponentName());
                if (componentStatusDetails.getComponentName().equals("SimpleApp")) {
                    assertEquals("2.0.0", componentStatusDetails.getVersion());
                    assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
                    assertEquals(MOCK_FLEET_CONFIG_ARN, componentStatusDetails.getFleetConfigArns().get(0));
                    assertEquals(State.FINISHED, componentStatusDetails.getState());
                    assertTrue(componentStatusDetails.isRoot());
                } else {
                    assertFalse(componentStatusDetails.isRoot());
                }
            });
        } catch (UnrecognizedPropertyException ignored) {
        }
        assertEquals(0, componentNamesToCheck.size());
        Slf4jLogAdapter.removeGlobalListener(logListener);
    }


    private void offerSampleIoTJobsDeployment(String fileName, String deploymentId) throws Exception {

        Path localStoreContentPath =
                Paths.get(IotJobsFleetStatusServiceTest.class.getResource("local_store_content").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"), kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(), REPLACE_EXISTING);

        DeploymentQueue deploymentQueue =
                (DeploymentQueue) kernel.getContext().getvIfExists(DeploymentQueue.class).get();
        Configuration deploymentConfiguration = OBJECT_MAPPER.readValue(new File(getClass().getResource(fileName).toURI()), Configuration.class);
        deploymentQueue.offer(new Deployment(OBJECT_MAPPER.writeValueAsString(deploymentConfiguration),
                DeploymentType.IOT_JOBS, deploymentId));
    }

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        DeploymentQueue deploymentQueue =
                (DeploymentQueue) kernel.getContext().getvIfExists(DeploymentQueue.class).get();
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }
}
