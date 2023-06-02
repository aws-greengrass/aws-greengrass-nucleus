/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.amazon.aws.iot.greengrass.configuration.common.DeploymentCapability;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.IotJobsClientWrapper;
import com.aws.greengrass.deployment.IotJobsHelper;
import com.aws.greengrass.deployment.ThingGroupHelper;
import com.aws.greengrass.deployment.exceptions.DeploymentRejectedException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.status.model.DeploymentInformation;
import com.aws.greengrass.status.model.FleetStatusDetails;
import com.aws.greengrass.status.model.Trigger;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.GG_DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;


public class SubGroupDeploymentIntegrationTest extends BaseITCase {

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final String THING_GROUP_PREFIX = "thinggroup/";
    private static final String ROOT_GROUP_NAME = "parentGroup";
    private static final String ROOT_GROUP_DEPLOYMENT_CONFIG = "FleetConfigWithSimpleAppv1.json";
    private static final Map<String, String> ROOT_GROUP_SERVICE_MAP = Utils.immutableMap("SimpleApp", "1.0.0");
    private static final List<String> REQUIRED_CAPABILITY =
            Arrays.asList(DeploymentCapability.SUB_DEPLOYMENTS.toString());

    private Kernel kernel;
    private DeviceConfiguration deviceConfiguration;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;
    @Mock
    private ThingGroupHelper thingGroupHelper;
    @Mock
    private MqttClient mqttClient;
    private IotJobsClientWrapper iotJobsClientWrapper;
    private Map<String, CountDownLatch> groupLatchMap;
    private Map<String, Map<String, Object>> groupDeploymentStatus;
    private Map<String, AtomicReference<FleetStatusDetails>> fleetStatusUpdates;
    private Map<String, UpdateJobExecutionRequest> jobStatusUpdates;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        groupDeploymentStatus = new ConcurrentHashMap<>();
        fleetStatusUpdates = new ConcurrentHashMap<>();
        jobStatusUpdates = new ConcurrentHashMap<>();
        groupLatchMap = new ConcurrentHashMap<>();

        kernel = new Kernel();
        kernel.getContext().put(ThingGroupHelper.class, thingGroupHelper);
        kernel.getContext().put(MqttClient.class, mqttClient);

        NoOpPathOwnershipHandler.register(kernel);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel, this.getClass().getResource("onlyMain.yaml"));

        // ensure deployment service starts
        CountDownLatch deploymentServiceLatch = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(DEPLOYMENT_SERVICE_TOPICS) && newState.equals(State.RUNNING)) {
                deploymentServiceLatch.countDown();
            }
        });

        // set up device config
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 0L);

        // listen to mqtt deployment status updates
        when(mqttClient.getMqttOperationTimeoutMillis()).thenReturn(10000);
        when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
            PublishRequest publishRequest = i.getArgument(0);
            if (publishRequest.getTopic().endsWith("namespace-aws-gg-deployment/update")) {
                try {
                    UpdateJobExecutionRequest updateJobExecReq =
                            OBJECT_MAPPER.readValue(publishRequest.getPayload(), UpdateJobExecutionRequest.class);
                    String resource = Arn.fromString(updateJobExecReq.jobId).resource().resource();
                    String groupName = resource;
                    if (resource.startsWith(THING_GROUP_PREFIX)) {
                        groupName = groupName.substring(THING_GROUP_PREFIX.length());
                    }
                    jobStatusUpdates.put(groupName, updateJobExecReq);
                } catch (JsonMappingException ignored) {
                }
            }

            if (publishRequest.getTopic().endsWith("greengrassv2/health/json")) {
                try {
                    FleetStatusDetails statusDetails =
                            OBJECT_MAPPER.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
                    if (Trigger.THING_GROUP_DEPLOYMENT.equals(statusDetails.getTrigger())) {
                        // record only deployment status
                        String groupName = statusDetails.getDeploymentInformation().getDeploymentId();
                        AtomicReference<FleetStatusDetails> ref =
                                fleetStatusUpdates.getOrDefault(groupName, new AtomicReference<>());
                        ref.set(statusDetails);
                        fleetStatusUpdates.put(groupName, ref);
                    }
                } catch (JsonMappingException ignored) {
                }
            }
            return CompletableFuture.completedFuture(0);
        });

        // launch kernel
        kernel.launch();
        assertTrue(deploymentServiceLatch.await(10, TimeUnit.SECONDS));

        // get kernel details after launch
        localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());
        deploymentQueue = kernel.getContext().get(DeploymentQueue.class);

        // setup spy device configuration for FSS
        deviceConfiguration = spy(kernel.getContext().get(DeviceConfiguration.class));
        doNothing().when(deviceConfiguration).validate();

        // setup fss such that it could send mqtt messages to the mock listener
        FleetStatusService fleetStatusService = (FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS);
        fleetStatusService.setDeviceConfiguration(deviceConfiguration);
        fleetStatusService.postInject();

        // setup jobs helper such that it could send mqtt messages to the mock listener
        IotJobsHelper jobsHelper = kernel.getContext().get(IotJobsHelper.class);
        jobsHelper.setDeviceConfiguration(deviceConfiguration);
        jobsHelper.postInject();
        iotJobsClientWrapper = spy(jobsHelper.getIotJobsClientWrapper());

        // mimic response back from cloud to update job accepted topic
        doAnswer((i) -> {
            Consumer<UpdateJobExecutionResponse> handler = i.getArgument(2);
            handler.accept(new UpdateJobExecutionResponse());
            return CompletableFuture.completedFuture(0);
        }).when(iotJobsClientWrapper)
                .SubscribeToUpdateJobExecutionAccepted(any(UpdateJobExecutionSubscriptionRequest.class),
                        any(QualityOfService.class), any(Consumer.class));

        // replace with spied version
        jobsHelper.setIotJobsClientWrapper(iotJobsClientWrapper);
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_root_deployment_success_WHEN_subgroup_deploy_with_component_changes_THEN_deployment_overrides()
            throws Exception {
        // Given
        // deploys SimpleApp 1.0.0
        setupRootParentGroupDeploymentFor("subGroup1");

        // When
        // sub-group deployment overrides SimpleApp to 2.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv2.json", 1, "subGroup1", ROOT_GROUP_NAME);

        // Then
        verifyServices(Utils.immutableMap("SimpleApp", "2.0.0"));
    }

    @Test
    void GIVEN_root_deployment_success_WHEN_multiple_sibling_subgroups_deploy_with_different_component_versions_and_deploy_received_in_order_THEN_most_recent_created_deploy_wins()
            throws Exception {
        // Given
        // deploys SimpleApp 1.0.0
        setupRootParentGroupDeploymentFor("subGroup1", "subGroup2", "subGroup3");

        // When
        // sub-group deployment maintains SimpleApp 1.0.0 and adds GreenSignal 1.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv1AndGreenSignal.json", 1, "subGroup1",
                ROOT_GROUP_NAME);
        verifyServices(Utils.immutableMap("SimpleApp", "1.0.0", "GreenSignal", "1.0.0"));
        // sub-group deployment overrides SimpleApp to 2.0.0 and removes GreenSignal
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv2.json", 1, "subGroup2", ROOT_GROUP_NAME);
        // sub-group deployment overrides SimpleApp to 3.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv3.json", 1, "subGroup3", ROOT_GROUP_NAME);

        // Then
        verifyServices(Utils.immutableMap("SimpleApp", "3.0.0"));
        verifyServicesRemoved("GreenSignal");
    }

    @Test
    void GIVEN_root_deployment_success_WHEN_nested_subgroups_deploy_with_different_component_versions_and_deploy_received_in_order_THEN_most_recent_created_deploy_wins()
            throws Exception {
        // Given
        // deploys SimpleApp 1.0.0
        setupRootParentGroupDeploymentFor("subGroup1", "subGroup2", "subGroup11", "subGroup12");

        // When
        // redeployment without change
        createSubGroupDeploymentAndWait(ROOT_GROUP_DEPLOYMENT_CONFIG, 1, "subGroup1", ROOT_GROUP_NAME);
        // redeployment without change
        createSubGroupDeploymentAndWait(ROOT_GROUP_DEPLOYMENT_CONFIG, 1, "subGroup2", ROOT_GROUP_NAME);
        // sub-group deployment overrides SimpleApp to 2.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv2.json", 1, "subGroup11", "subGroup1");
        // sub-group deployment overrides SimpleApp to 3.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv3.json", 1, "subGroup12", "subGroup1");

        // Then
        verifyServices(Utils.immutableMap("SimpleApp", "3.0.0"));
    }

    @Test
    void GIVEN_root_deployment_success_WHEN_nested_subgroups_deploy_with_different_component_versions_and_deploy_received_not_in_order_THEN_stale_deployment_rejected(
            ExtensionContext context) throws Exception {
        // expect a rejection exception
        ignoreExceptionOfType(context, DeploymentRejectedException.class);

        // Given
        // deploys SimpleApp 1.0.0
        setupRootParentGroupDeploymentFor("subGroup1", "subGroup2", "subGroup3");

        // When
        // sub-group deployment maintains SimpleApp 1.0.0 and adds GreenSignal 1.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv1AndGreenSignal.json", 1, "subGroup1",
                ROOT_GROUP_NAME);
        verifyServices(Utils.immutableMap("SimpleApp", "1.0.0", "GreenSignal", "1.0.0"));
        // create a stale deployment which should override SimpleApp to 2.0.0
        Deployment oldDeployment =
                createDeployment("FleetConfigWithSimpleAppv2.json", 1, "subGroup2", ROOT_GROUP_NAME, ROOT_GROUP_NAME,
                        false);
        // sub-group deployment overrides SimpleApp to 3.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv3.json", 1, "subGroup3", ROOT_GROUP_NAME);
        // queue stale deployment received in bad order, which should get rejected
        queueDeploymentAndWait("subGroup2", oldDeployment);

        // Then
        verifyServices(Utils.immutableMap("SimpleApp", "3.0.0"));
        verifyServicesRemoved("GreenSignal");
        verifyRejectionStatus("subGroup2");
    }

    @Test
    void GIVEN_root_deployment_success_with_multiple_sibling_subgroups_WHEN_new_root_deployment_revision_THEN_root_deployment_overrides_subgroups()
            throws Exception {
        // Given
        // deploys SimpleApp 1.0.0
        setupRootParentGroupDeploymentFor("subGroup1", "subGroup2", "subGroup3");

        // When
        // sub-group deployment maintains SimpleApp 1.0.0 and adds GreenSignal 1.0.0
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv1AndGreenSignal.json", 1, "subGroup1",
                ROOT_GROUP_NAME);
        verifyServices(Utils.immutableMap("SimpleApp", "1.0.0", "GreenSignal", "1.0.0"));
        // sub-group deployment overrides SimpleApp to 2.0.0 and removes GreenSignal
        createSubGroupDeploymentAndWait("FleetConfigWithSimpleAppv2.json", 1, "subGroup2", ROOT_GROUP_NAME);

        // deploys revision overrides all sub-groups to SimpleApp 3.0.0
        groupLatchMap.put(ROOT_GROUP_NAME, new CountDownLatch(1));
        createRootParentDeploymentAndWait("FleetConfigWithSimpleAppv3.json", 2);

        // Then
        verifyServices(Utils.immutableMap("SimpleApp", "3.0.0"));
        verifyServicesRemoved("GreenSignal");
    }

    private void verifyServices(Map<String, String> serviceVersions) {
        Topics groupToRootTopic = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS, GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList(THING_GROUP_PREFIX + ROOT_GROUP_NAME)),
                "Device should only " + "belong to " + ROOT_GROUP_NAME);

        Map<GreengrassService, DependencyType> dependenciesAfter = kernel.getMain().getDependencies();
        List<String> serviceNames =
                dependenciesAfter.keySet().stream().map(service -> service.getName()).collect(Collectors.toList());
        assertTrue(serviceNames.containsAll(serviceVersions.keySet()));

        for (Map.Entry<String, String> serviceEntry : serviceVersions.entrySet()) {
            // check service version
            Topic serviceVersion =
                    groupToRootTopic.find(THING_GROUP_PREFIX + ROOT_GROUP_NAME, serviceEntry.getKey(), "version");
            assertEquals(serviceEntry.getValue(), Coerce.toString(serviceVersion.getOnce()));
        }
    }

    private void verifyServicesRemoved(String... services) {
        Topics groupToRootTopic = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS, GROUP_TO_ROOT_COMPONENTS_TOPICS);
        for (String service : services) {
            Topic serviceVersion = groupToRootTopic.find(THING_GROUP_PREFIX + ROOT_GROUP_NAME, service, "version");
            assertNull(serviceVersion);
        }
    }

    private void verifyRejectionStatus(String... groups) {
        for (String groupName : groups) {
            Map<String, Object> statusDetails = groupDeploymentStatus.get(groupName);
            String deploymentStatus = (String) statusDetails.get(DEPLOYMENT_STATUS_KEY_NAME);
            assertEquals("REJECTED", deploymentStatus);

            // verify fleet status update
            assertThat(() -> fleetStatusUpdates.get(groupName),
                    eventuallyEval(notNullValue(AtomicReference.class), Duration.ofSeconds(20)));
            FleetStatusDetails statusUpdate = fleetStatusUpdates.get(groupName).get();
            DeploymentInformation deploymentInfo = statusUpdate.getDeploymentInformation();
            assertEquals("REJECTED", deploymentInfo.getStatus());
            assertNotNull(deploymentInfo.getStatusDetails());
            assertEquals("REJECTED", deploymentInfo.getStatusDetails().getDetailedStatus());
            assertThat("Rejected error stack in status update doesn't match",
                    deploymentInfo.getStatusDetails().getErrorStack(),
                    contains("DEPLOYMENT_REJECTED", "REJECTED_STALE_DEPLOYMENT"));

            // verify job status update
            assertThat(() -> jobStatusUpdates.get(groupName),
                    eventuallyEval(notNullValue(UpdateJobExecutionRequest.class), Duration.ofSeconds(20)));
            UpdateJobExecutionRequest updateJobExecReq = jobStatusUpdates.get(groupName);
            assertEquals(JobStatus.REJECTED, updateJobExecReq.status);
            assertNotNull(updateJobExecReq.statusDetails);
            assertEquals("REJECTED", updateJobExecReq.statusDetails
                    .get(DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY));
            String jobsErrorStack = updateJobExecReq.statusDetails.get(DeploymentService.DEPLOYMENT_ERROR_STACK_KEY);
            assertThat("Rejected error stack in update job doesn't match", jobsErrorStack,
                    containsString("DEPLOYMENT_REJECTED"));
            assertThat("Rejected error stack in update job doesn't match", jobsErrorStack,
                    containsString("REJECTED_STALE_DEPLOYMENT"));
        }
    }

    private void setupRootParentGroupDeploymentFor(@Nonnull String... subGroupNames) throws Exception {
        for (String subGroup : subGroupNames) {
            groupLatchMap.put(subGroup, new CountDownLatch(1));
        }
        groupLatchMap.put(ROOT_GROUP_NAME, new CountDownLatch(1));

        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            String groupName = (String) status.get(GG_DEPLOYMENT_ID_KEY_NAME);
            String deploymentStatus = (String) status.get(DEPLOYMENT_STATUS_KEY_NAME);
            groupDeploymentStatus.put(groupName, status);
            if ("SUCCEEDED".equals(deploymentStatus) || "REJECTED".equals(deploymentStatus)) {
                CountDownLatch latch = groupLatchMap.get(groupName);
                if (latch != null) {
                    latch.countDown();
                }
            }
            return true;
        }, "dummyValue");

        final Set<String> thingGroups = new HashSet<>(groupLatchMap.size());
        thingGroups.addAll(groupLatchMap.keySet());

        when(thingGroupHelper.listThingGroupsForDevice(anyInt())).thenReturn(
                Optional.of(Collections.unmodifiableSet(thingGroups)));
        createRootParentDeploymentAndWait(ROOT_GROUP_DEPLOYMENT_CONFIG, 1);
        verifyServices(ROOT_GROUP_SERVICE_MAP);
    }

    private void createSubGroupDeploymentAndWait(String jsonDoc, int revision, String subGroupName,
                                                 String parentGroupName) throws Exception {
        createDeployment(jsonDoc, revision, subGroupName, parentGroupName, ROOT_GROUP_NAME, true);
    }

    private void createRootParentDeploymentAndWait(String jsonDoc, int revision) throws Exception {
        createDeployment(jsonDoc, revision, ROOT_GROUP_NAME, null, null, true);
    }

    private Deployment createDeployment(String jsonDoc, int revision, String targetGroup, String parentGroup,
                                        String onBehalfOf, boolean startDeployment) throws Exception {
        // to create visible gaps between deployments sleep for sometime
        Thread.sleep(1000L);

        URI uri = this.getClass().getResource(jsonDoc).toURI();
        Configuration deploymentConfiguration = OBJECT_MAPPER.readValue(new File(uri), Configuration.class);
        deploymentConfiguration.setCreationTimestamp(System.currentTimeMillis());
        deploymentConfiguration.setConfigurationArn(
                String.format("arn:aws:greengrass:us-east-1:123456789012" + ":configuration:thinggroup/%s:%d",
                        targetGroup, revision));
        deploymentConfiguration.setDeploymentId(targetGroup);
        deploymentConfiguration.setParentTargetArn(parentGroup == null ? null
                : String.format("arn:aws:iot:us-east-1:123456789012:thinggroup/%s", parentGroup));
        deploymentConfiguration.setOnBehalfOf(onBehalfOf == null ? null
                : String.format("arn:aws:iot:us-east-1:123456789012:thinggroup/%s", onBehalfOf));
        deploymentConfiguration.setRequiredCapabilities(REQUIRED_CAPABILITY);

        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(deploymentConfiguration),
                Deployment.DeploymentType.IOT_JOBS, deploymentConfiguration.getConfigurationArn());
        if (startDeployment) {
            queueDeploymentAndWait(targetGroup, deployment);
        }
        return deployment;
    }

    private void queueDeploymentAndWait(String groupName, Deployment deployment)
            throws IOException, InterruptedException {
        // need to copy for each deployment because component clean up happens after each deployment.
        copyRecipeAndArtifacts();
        deploymentQueue.offer(deployment);
        assertTrue(groupLatchMap.get(groupName).await(10, TimeUnit.SECONDS));
    }

    private void copyRecipeAndArtifacts() throws IOException {
        // pre-load contents to package store
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
    }
}
