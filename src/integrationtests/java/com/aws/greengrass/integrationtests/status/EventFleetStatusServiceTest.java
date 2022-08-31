/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.status;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.IotJobsClientWrapper;
import com.aws.greengrass.deployment.IotJobsHelper;
import com.aws.greengrass.deployment.ThingGroupHelper;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorType;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.status.model.FleetStatusDetails;
import com.aws.greengrass.status.model.MessageType;
import com.aws.greengrass.status.model.OverallStatus;
import com.aws.greengrass.status.model.Trigger;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.fasterxml.jackson.databind.JsonMappingException;
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
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.ResolveComponentCandidatesRequest;
import software.amazon.awssdk.services.greengrassv2data.model.ResourceNotFoundException;

import java.io.EOFException;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.IotJobsHelper.UPDATE_DEPLOYMENT_STATUS_ACCEPTED;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.TestUtils.createCloseableLogListener;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("PMD.CouplingBetweenObjects")
@ExtendWith({GGExtension.class, MockitoExtension.class})
class EventFleetStatusServiceTest extends BaseITCase {

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

    private AtomicReference<List<FleetStatusDetails>> fleetStatusDetailsList;

    @Captor
    private ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> jobsAcceptedHandlerCaptor;

    @SuppressWarnings("PMD.CloseResource")
    @BeforeEach
    void setupKernel(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, TLSAuthException.class);
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionUltimateCauseOfType(context, EOFException.class);
        ignoreExceptionUltimateCauseOfType(context, ResourceNotFoundException.class);

        CountDownLatch fssRunning = new CountDownLatch(1);
        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        CompletableFuture<Void> cf = new CompletableFuture<>();
        fleetStatusDetailsList = new AtomicReference<>(new ArrayList<>());
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
        when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
            Object argument = i.getArgument(0);
            PublishRequest publishRequest = (PublishRequest) argument;
            try {
                FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(publishRequest.getPayload(),
                        FleetStatusDetails.class);
                // filter all event-triggered fss messages
                if (fleetStatusDetails.getMessageType() == MessageType.PARTIAL) {
                    fleetStatusDetailsList.get().add(fleetStatusDetails);
                }
            } catch (JsonMappingException ignored) { }
            return CompletableFuture.completedFuture(0);
        });
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                EventFleetStatusServiceTest.class.getResource("onlyMain.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(ThingGroupHelper.class, thingGroupHelper);

        // Mock out cloud communication
        GreengrassServiceClientFactory mgscf = mock(GreengrassServiceClientFactory.class);
        GreengrassV2DataClient mcf = mock(GreengrassV2DataClient.class);
        lenient().when(mcf.resolveComponentCandidates(any(ResolveComponentCandidatesRequest.class)))
                .thenThrow(ResourceNotFoundException.class);
        lenient().when(mgscf.fetchGreengrassV2DataClient()).thenReturn(mcf);
        kernel.getContext().put(GreengrassServiceClientFactory.class, mgscf);

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
                Paths.get(EventFleetStatusServiceTest.class.getResource("local_store_content").toURI());
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
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("THING_GROUP_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignoredListener = createCloseableLogListener(logListener)) {

            offerSampleIoTJobsDeployment("FleetStatusServiceConfig.json", TEST_JOB_ID_1);
            assertTrue(fssPublishLatch.await(60, TimeUnit.SECONDS));

            assertEquals(1, fleetStatusDetailsList.get().size());
            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
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
            assertEquals(0, componentNamesToCheck.size());
        } catch (UnrecognizedPropertyException ignored) {
        }
    }

    @Test
    void GIVEN_jobs_deployment_WHEN_deployment_fails_with_component_broken_THEN_error_stack_is_uploaded_to_cloud(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, InvocationTargetException.class);
        ignoreExceptionOfType(context, ServiceUpdateException.class);
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get()).put("plugin",
                GreengrassService.class.getName());
        assertNotNull(deviceConfiguration.getThingName());
        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getEventType() != null && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("THING_GROUP_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignoredListener = createCloseableLogListener(logListener)) {

            offerSampleIoTJobsDeployment("FSSBrokenComponentConfig.json", TEST_JOB_ID_1);
            assertTrue(fssPublishLatch.await(60, TimeUnit.SECONDS));

            assertEquals(1, fleetStatusDetailsList.get().size());
            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertEquals(OverallStatus.UNHEALTHY, fleetStatusDetails.getOverallStatus());
            assertListEquals(Arrays.asList(DeploymentErrorCode.DEPLOYMENT_FAILURE.name(),
                            DeploymentErrorCode.COMPONENT_UPDATE_ERROR.name(),
                            DeploymentErrorCode.COMPONENT_BROKEN.name()),
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getErrorStack());
            assertListEquals(Collections.singletonList(DeploymentErrorType.COMPONENT_ERROR.name()),
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getErrorTypes());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size());
            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                componentNamesToCheck.remove(componentStatusDetails.getComponentName());
                switch (componentStatusDetails.getComponentName()) {
                    case "CustomerApp":
                        assertEquals("1.0.0", componentStatusDetails.getVersion());
                        assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
                        assertEquals(MOCK_FLEET_CONFIG_ARN, componentStatusDetails.getFleetConfigArns().get(0));
                        assertEquals(State.FINISHED, componentStatusDetails.getState());
                        assertTrue(componentStatusDetails.isRoot());
                        break;
                    case "Mosquitto":
                        assertEquals("1.0.0", componentStatusDetails.getVersion());
                        assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
                        assertEquals(MOCK_FLEET_CONFIG_ARN, componentStatusDetails.getFleetConfigArns().get(0));
                        assertEquals(State.RUNNING, componentStatusDetails.getState());
                        assertFalse(componentStatusDetails.isRoot());
                        break;
                    case "BrokenRun":
                        assertEquals("1.0.0", componentStatusDetails.getVersion());
                        assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
                        assertEquals(MOCK_FLEET_CONFIG_ARN, componentStatusDetails.getFleetConfigArns().get(0));
                        assertEquals(State.BROKEN, componentStatusDetails.getState());
                        assertTrue(componentStatusDetails.isRoot());
                        break;
                    default:
                        assertFalse(componentStatusDetails.isRoot());
                        break;
                }
            });
            assertEquals(0, componentNamesToCheck.size());
        } catch (UnrecognizedPropertyException ignored) {
        }
    }

    @Test
    void GIVEN_local_deployment_WHEN_deployment_finishes_THEN_status_is_uploaded_to_cloud() throws Exception {
        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getEventType() != null && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("LOCAL_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignored = createCloseableLogListener(logListener)) {

            // Local deployment adding SimpleApp v1
            Map<String, String> componentsToMerge = new HashMap<>();
            componentsToMerge.put("SimpleApp", "1.0.0");
            LocalOverrideRequest request =
                    LocalOverrideRequest.builder().requestId("SimpleApp1").componentsToMerge(componentsToMerge).requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(request);

            assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
            assertEquals(1, fleetStatusDetailsList.get().size());
            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
            // Get the last FSS publish request which should have component info of simpleApp v1 and other built in services
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size());
            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                componentNamesToCheck.remove(componentStatusDetails.getComponentName());
                if (componentStatusDetails.getComponentName().equals("SimpleApp")) {
                    assertEquals("1.0.0", componentStatusDetails.getVersion());
                    assertEquals(State.FINISHED, componentStatusDetails.getState());
                    assertTrue(componentStatusDetails.isRoot());
                } else {
                    assertFalse(componentStatusDetails.isRoot());
                }
            });
            assertEquals(0, componentNamesToCheck.size());
        }
    }

    @Test
    void GIVEN_local_deployment_WHEN_deployment_fails_with_component_broken_THEN_error_stack_is_uploaded_to_cloud(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getEventType() != null && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("LOCAL_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignored = createCloseableLogListener(logListener)) {

            // Local deployment adding BrokenRun v1
            Map<String, String> componentsToMerge = new HashMap<>();
            componentsToMerge.put("BrokenRun", "1.0.0");
            LocalOverrideRequest request =
                    LocalOverrideRequest.builder().requestId("BrokenRun").componentsToMerge(componentsToMerge).requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(request);

            assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
            assertEquals(1, fleetStatusDetailsList.get().size());
            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
            // Get the last FSS publish request which should have component info of simpleApp v1 and other built in services
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.UNHEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size());
            assertListEquals(Arrays.asList(DeploymentErrorCode.DEPLOYMENT_FAILURE.name(),
                            DeploymentErrorCode.COMPONENT_UPDATE_ERROR.name(),
                            DeploymentErrorCode.COMPONENT_BROKEN.name()),
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getErrorStack());
            assertListEquals(Collections.singletonList(DeploymentErrorType.USER_COMPONENT_ERROR.name()),
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getErrorTypes());
            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                componentNamesToCheck.remove(componentStatusDetails.getComponentName());
                if (componentStatusDetails.getComponentName().equals("BrokenRun")) {
                    assertEquals("1.0.0", componentStatusDetails.getVersion());
                    assertEquals(State.BROKEN, componentStatusDetails.getState());
                    assertTrue(componentStatusDetails.isRoot());
                } else {
                    assertFalse(componentStatusDetails.isRoot());
                }
            });
            assertEquals(0, componentNamesToCheck.size());
        }
    }

    @Test
    void GIVEN_local_deployment_WHEN_deployment_fails_with_invalid_component_recipe_THEN_error_stack_is_uploaded_to_cloud(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ExecutionException.class);
        ignoreExceptionOfType(context, PackageLoadingException.class);

        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getEventType() != null && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("LOCAL_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignored = createCloseableLogListener(logListener)) {

            // Local deployment adding BrokenRun v1
            Map<String, String> componentsToMerge = new HashMap<>();
            componentsToMerge.put("AppInvalidRecipe", "1.0.0");
            LocalOverrideRequest request =
                    LocalOverrideRequest.builder().requestId("AppInvalidRecipeDeployment").componentsToMerge(componentsToMerge).requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(request);

            assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
            assertEquals(1, fleetStatusDetailsList.get().size());
            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
            // Get the last FSS publish request which should have component info of simpleApp v1 and other built in services
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            // DEPLOYMENT_FAILURE, COMPONENT_PACKAGE_LOADING_ERROR, IO_ERROR, IO_MAPPING_ERROR, RECIPE_PARSE_ERROR
            assertListEquals(Arrays.asList(DeploymentErrorCode.DEPLOYMENT_FAILURE.name(),
                            DeploymentErrorCode.COMPONENT_PACKAGE_LOADING_ERROR.name(),
                            DeploymentErrorCode.IO_ERROR.name(),
                            DeploymentErrorCode.IO_MAPPING_ERROR.name(),
                            DeploymentErrorCode.RECIPE_PARSE_ERROR.name()),
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getErrorStack());
            assertListEquals(Collections.singletonList(DeploymentErrorType.COMPONENT_RECIPE_ERROR.name()),
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getErrorTypes());
            assertEquals("AppInvalidRecipeDeployment", fleetStatusDetails.getDeploymentInformation().getDeploymentId());
            assertEquals("FAILED_NO_STATE_CHANGE",
                    fleetStatusDetails.getDeploymentInformation().getStatusDetails().getDetailedStatus());
        }
    }

    @Test
    void GIVEN_deployment_with_no_component_changes_WHEN_deployment_finishes_THEN_status_is_uploaded_to_cloud() throws Exception {
        CountDownLatch fssPublishLatch = new CountDownLatch(2);
        logListener = eslm -> {
            if (eslm.getEventType() != null
                    && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("LOCAL_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignored = createCloseableLogListener(logListener)) {

            // First deployment adding SimpleApp v1
            Map<String, String> componentsToMerge = new HashMap<>();
            componentsToMerge.put("SimpleApp", "1.0.0");
            LocalOverrideRequest firstRequest =
                    LocalOverrideRequest.builder().requestId("SimpleApp1-1").componentsToMerge(componentsToMerge).requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(firstRequest);

            // Second deployment adding SimpleApp v1 again
            LocalOverrideRequest secondRequest =
                    LocalOverrideRequest.builder().requestId("SimpleApp1-2").componentsToMerge(componentsToMerge).requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(secondRequest);

            assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
            assertEquals(2, fleetStatusDetailsList.get().size());

            // Get the last FSS publish request which should have no new component info, since no component statuses have
            // changed
            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(1);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(Trigger.LOCAL_DEPLOYMENT, fleetStatusDetails.getTrigger());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            assertEquals(0, fleetStatusDetails.getComponentStatusDetails().size());
        }
    }

    @Test
    void WHEN_jobs_deployment_bumps_up_component_version_THEN_status_of_new_version_is_updated_to_cloud() throws Exception {
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
                    && eslm.getMessage().contains("Status update published to FSS")
                    && eslm.getContexts().get("trigger").equals("THING_GROUP_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignoredListener = createCloseableLogListener(logListener)) {
            // First local deployment adds SimpleApp v1
            Map<String, String> componentsToMerge = new HashMap<>();
            componentsToMerge.put("SimpleApp", "1.0.0");
            LocalOverrideRequest request =
                    LocalOverrideRequest.builder().requestId("SimpleApp1").componentsToMerge(componentsToMerge).requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(request);
            // Second local deployment removes SimpleApp v1
            request = LocalOverrideRequest.builder().requestId("removeSimpleApp")
                    .componentsToRemove(Arrays.asList("SimpleApp")).requestTimestamp(System.currentTimeMillis())
                    .build();
            submitLocalDocument(request);
            // Cloud deployment adds SimpleApp v2.
            offerSampleIoTJobsDeployment("FleetConfigSimpleApp2.json", "simpleApp2");
            assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
            assertEquals(3, fleetStatusDetailsList.get().size());

            FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(2);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(Trigger.THING_GROUP_DEPLOYMENT, fleetStatusDetails.getTrigger());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            // Last deployment had only 1 component + "main" in fss update ComponentStatusDetails
            assertEquals(2, fleetStatusDetails.getComponentStatusDetails().size());

            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
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
    }

    @Test
    void WHEN_deployment_of_any_type_is_processed_THEN_fss_sends_correct_deployment_id_and_configuration_arn()
            throws Exception {
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get()).put("plugin",
                GreengrassService.class.getName());
        assertNotNull(deviceConfiguration.getThingName());
        CountDownLatch jobsDeploymentLatch = new CountDownLatch(1);
        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getMessage() != null && eslm.getMessage().equals(UPDATE_DEPLOYMENT_STATUS_ACCEPTED)
                    && eslm.getContexts().get("JobId").equals("iot_jobs_deployment")) {
                jobsDeploymentLatch.countDown();
            }
            if (jobsDeploymentLatch.getCount() == 0 && eslm.getEventType() != null && eslm.getEventType()
                    .equals("fss-status-update-published") && eslm.getMessage()
                    .contains("Status update published to FSS") && eslm.getContexts().get("trigger")
                    .equals("THING_GROUP_DEPLOYMENT")) {
                fssPublishLatch.countDown();
            }
        };
        try (AutoCloseable ignoredListener = createCloseableLogListener(logListener)) {
            // First Local deployment adds CustomerApp 1.0.0
            LocalOverrideRequest request = LocalOverrideRequest.builder().requestId("local_deployment")
                    .componentsToMerge(Collections.singletonMap("CustomerApp", "1.0.0"))
                    .requestTimestamp(System.currentTimeMillis()).build();
            submitLocalDocument(request);
            // Second Shadow deployment adds Mosquitto 1.0.0
            submitShadowDeployment("ShadowDeploymentForMosquitto.json", "shadow_deployment");
            // Third IoT Jobs deployment adds SimpleApp 1.0.0.
            offerSampleIoTJobsDeployment("FleetConfigSimpleApp.json", "iot_jobs_deployment");
            assertTrue(fssPublishLatch.await(180, TimeUnit.SECONDS));
            assertEquals(3, fleetStatusDetailsList.get().size());
            fleetStatusDetailsList.get().forEach(status -> {
                assertEquals("ThingName", status.getThing());
                assertEquals(OverallStatus.HEALTHY, status.getOverallStatus());
                assertEquals(MessageType.PARTIAL, status.getMessageType());
                assertNull(status.getChunkInfo());
                assertNotNull(status.getComponentStatusDetails());
                assertNotNull(status.getDeploymentInformation());
            });

            FleetStatusDetails localDeploymentStatus = fleetStatusDetailsList.get().get(0);
            assertEquals(Trigger.LOCAL_DEPLOYMENT, localDeploymentStatus.getTrigger());
            assertEquals("local_deployment", localDeploymentStatus.getDeploymentInformation().getDeploymentId());
            // Local deployments cannot have ARNs
            assertNull(localDeploymentStatus.getDeploymentInformation().getFleetConfigurationArnForStatus());

            FleetStatusDetails shadowDeploymentStatus = fleetStatusDetailsList.get().get(1);
            assertEquals(Trigger.THING_DEPLOYMENT, shadowDeploymentStatus.getTrigger());
            assertEquals("shadow_deployment", shadowDeploymentStatus.getDeploymentInformation().getDeploymentId());
            assertEquals("arn:aws:greengrass:us-east-1:12345678910:configuration:thing/ThingName:1",
                    shadowDeploymentStatus.getDeploymentInformation().getFleetConfigurationArnForStatus());

            FleetStatusDetails iotJobsDeploymentStatus = fleetStatusDetailsList.get().get(2);
            assertEquals(Trigger.THING_GROUP_DEPLOYMENT, iotJobsDeploymentStatus.getTrigger());
            assertEquals("iot_jobs_deployment", iotJobsDeploymentStatus.getDeploymentInformation().getDeploymentId());
            assertEquals("arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1",
                    iotJobsDeploymentStatus.getDeploymentInformation().getFleetConfigurationArnForStatus());
        } catch (UnrecognizedPropertyException ignored) {
        }
    }

    private void offerSampleIoTJobsDeployment(String fileName, String deploymentId) throws Exception {
        submitCloudDeployment(fileName, deploymentId, DeploymentType.IOT_JOBS);
    }

    private void submitShadowDeployment(String fileName, String deploymentId) throws Exception {
        submitCloudDeployment(fileName, deploymentId, DeploymentType.SHADOW);
    }

    private void submitCloudDeployment(String fileName, String deploymentId, DeploymentType type) throws Exception {
        Path localStoreContentPath =
                Paths.get(EventFleetStatusServiceTest.class.getResource("local_store_content").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"), kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(), REPLACE_EXISTING);

        DeploymentQueue deploymentQueue =
                (DeploymentQueue) kernel.getContext().getvIfExists(DeploymentQueue.class).get();
        Configuration deploymentConfiguration = OBJECT_MAPPER.readValue(new File(getClass().getResource(fileName).toURI()), Configuration.class);
        deploymentQueue.offer(new Deployment(OBJECT_MAPPER.writeValueAsString(deploymentConfiguration),
                type, deploymentId));
    }

    private void submitLocalDocument(LocalOverrideRequest request) throws Exception {
        DeploymentQueue deploymentQueue =
                (DeploymentQueue) kernel.getContext().getvIfExists(DeploymentQueue.class).get();
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }

    private void assertListEquals(List<String> first, List<String> second) {
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i), second.get(i));
        }
    }
}
