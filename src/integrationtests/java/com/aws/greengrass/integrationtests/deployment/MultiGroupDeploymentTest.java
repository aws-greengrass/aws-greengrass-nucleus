/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.deployment;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentQueue;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.ThingGroupHelper;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionWithMessage;
import static com.aws.greengrass.util.Utils.copyFolderRecursively;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class})
public class MultiGroupDeploymentTest extends BaseITCase {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private Kernel kernel;
    private DeploymentQueue deploymentQueue;
    private Path localStoreContentPath;
    @Mock
    private ThingGroupHelper thingGroupHelper;

    @BeforeEach
    void before(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, PackageDownloadException.class);
        ignoreExceptionOfType(context, SdkClientException.class);

        kernel = new Kernel();
        kernel.getContext().put(ThingGroupHelper.class, thingGroupHelper);
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
        deploymentQueue = kernel.getContext().get(DeploymentQueue.class);

        FleetStatusService fleetStatusService = (FleetStatusService) kernel.locate(FLEET_STATUS_SERVICE_TOPICS);
        fleetStatusService.getIsConnected().set(false);
        localStoreContentPath =
                Paths.get(DeploymentTaskIntegrationTest.class.getResource("local_store_content").toURI());
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_device_belongs_to_two_groups_WHEN_device_receives_deployments_to_both_groups_THEN_no_components_removed()
            throws Exception {
        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();
            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                secondGroupCDL.countDown();

            }
            return true;
        }, "dummyValue");

        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("firstGroup", "secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json")
                .toURI(), "firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSomeService.json")
                .toURI(), "secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("firstGroup", "secondGroup")), "Device should belong to firstGroup and secondGroup");

        Map<GreengrassService, DependencyType> dependenciesAfter = kernel.getMain().getDependencies();
        List<String> serviceNames = dependenciesAfter.keySet().stream().map(service -> service.getName()).collect(Collectors.toList());
        assertTrue(serviceNames.containsAll(Arrays.asList("SomeService", "RedSignal")));

        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("SomeService", "secondGroup"));
        assertNotNull(componentsToGroupTopic.find("RedSignal", "firstGroup"));
    }

    @Test
    void GIVEN_device_belongs_to_two_groups_WHEN_device_is_removed_from_one_group_THEN_next_deployment_removes_corresponding_component() throws Exception {

        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();
            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                secondGroupCDL.countDown();

            }
            return true;
        }, "dummyValue");

        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("firstGroup", "secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json")
                .toURI(), "firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSomeService.json")
                .toURI(), "secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("secondGroup")), "Device should belong to firstGroup and secondGroup");

        Map<GreengrassService, DependencyType> dependenciesAfter = kernel.getMain().getDependencies();
        List<String> serviceNames = dependenciesAfter.keySet().stream().map(service -> service.getName()).collect(Collectors.toList());
        assertTrue(serviceNames.containsAll(Arrays.asList("SomeService")));

        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("SomeService", "secondGroup"));
    }

    @Test
    void GIVEN_two_groups_with_common_root_component_WHEN_device_is_removed_from_one_group_THEN_common_component_not_removed() throws Exception {

        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        CountDownLatch thirdGroupCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();
            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                secondGroupCDL.countDown();

            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thirdGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                thirdGroupCDL.countDown();

            }
            return true;
        }, "dummyValue");

        // deployment to firstGroup adds red signal and yellow signal
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("firstGroup", "secondGroup", "thirdGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedAndYellowService.json")
                .toURI(), "firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        // deployment to secondGroup adds red signal
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json")
                .toURI(), "secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        // verify group to root components mapping
        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("firstGroup", "secondGroup")));

        // verify components to group mapping
        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("RedSignal", "secondGroup"));
        assertNotNull(componentsToGroupTopic.find("RedSignal", "firstGroup"));

        //device gets removed from firstGroup,
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("secondGroup", "thirdGroup"))));
        // next deployment to thirdGroup will clean up root components only associated with firstGroup
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSomeService.json")
                .toURI(), "thirdGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(thirdGroupCDL.await(10, TimeUnit.SECONDS));

        // components belonging to only first group are removed, red signal should still be present as its associated with secondGroup
        groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);
        List<String> groups = new ArrayList<>();
        groupToRootTopic.forEach(node -> groups.add(node.getName()));
        assertTrue(groups.containsAll(Arrays.asList("secondGroup", "thirdGroup")), "Device should only belong to secondGroup and thirdGroup");

        Map<GreengrassService, DependencyType> dependenciesAfter = kernel.getMain().getDependencies();
        List<String> serviceNames = dependenciesAfter.keySet().stream().map(service -> service.getName()).collect(Collectors.toList());
        assertTrue(serviceNames.containsAll(Arrays.asList("SomeService", "RedSignal")));
        assertFalse(serviceNames.containsAll(Arrays.asList("YellowSignal")));

        componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("SomeService", "thirdGroup"));
        assertNotNull(componentsToGroupTopic.find("RedSignal", "secondGroup"));
        //mapping of regSignal to firstGroup is removed
        assertNull(componentsToGroupTopic.find("RedSignal", "firstGroup"));
    }

    @Test
    void GIVEN_groups_with_conflicting_components_WHEN_removed_from_one_group_THEN_deployment_succeeds()
            throws Exception {

        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();
            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                secondGroupCDL.countDown();

            }
            return true;
        }, "dummyValue");

        // deployment to firstGroup adds red signal and yellow signal
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("firstGroup", "secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSimpleAppv1.json")
                .toURI(), "firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("secondGroup"))));

        // deployment to secondGroup adds red signal
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSimpleAppv2.json")
                .toURI(), "secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        // verify group to root components mapping
        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("secondGroup")));

        Topic simpleAppVersion = groupToRootTopic.find("secondGroup", "SimpleApp", "version");
        assertEquals("2.0.0", Coerce.toString(simpleAppVersion.getOnce()));
    }


    @Test
    void GIVEN_device_is_removed_from_a_group_WHEN_next_deployment_fails_and_rollsback_THEN_removed_components_are_added_back(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ServiceUpdateException.class);

        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();
            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondGroup") && status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("FAILED") &&
                    ((Map) status.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME)).get("detailed-deployment-status")
                            .equals(DeploymentStatus.FAILED_ROLLBACK_COMPLETE.toString())) {
                secondGroupCDL.countDown();
            }
            return true;
        }, "dummyValue");

        // deployment to firstGroup adds red signal and yellow signal
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("firstGroup", "secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedAndYellowService.json")
                .toURI(), "firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        //device gets removed from firstGroup,
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("secondGroup"))));

        //second deployment will remove red signal and yellow signal, but the deployment fails due to broken service
        //rolling back will add back red signal/yellow signal. Mapping of groups to root components will also be restored.
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithBrokenService.json")
                .toURI(), "secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("firstGroup")));

        // verify components to group mapping
        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("YellowSignal", "firstGroup"));
        assertNotNull(componentsToGroupTopic.find("RedSignal", "firstGroup"));
    }

    @Test
    void GIVEN_device_is_removed_from_a_group_WHEN_next_deployment_fails_with_no_state_change_THEN_components_are_not_removed(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ExecutionException.class);
        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();
            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("secondGroup") && status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("FAILED") &&
                    ((Map) status.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME)).get("detailed-deployment-status")
                            .equals(DeploymentStatus.FAILED_NO_STATE_CHANGE.toString())) {
                secondGroupCDL.countDown();
            }
            return true;
        }, "test5");

        // deployment to firstGroup adds red signal and yellow signal
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("firstGroup", "secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedAndYellowService.json")
                .toURI(), "firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        //device gets removed from firstGroup,
        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("secondGroup"))));

        //second deployment fails with no state change, redsignal and yellow signal will not be removed and
        //group to root components mapping will not be updated.
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithNotDefinedService.json")
                .toURI(), "secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("firstGroup")));

        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("YellowSignal", "firstGroup"));
        assertNotNull(componentsToGroupTopic.find("RedSignal", "firstGroup"));
    }

    @Test
    void GIVEN_device_receives_deployment_from_multiple_groups_THEN_components_deployed_by_shadow_deployment_are_not_removed() throws Exception {
        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        CountDownLatch shadowDeploymentCDL = new CountDownLatch(1);
        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thinggroup/firstGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();

            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thinggroup/secondGroup") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                secondGroupCDL.countDown();

            }
            return true;
        }, "dummyValueIotJobs");

        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.SHADOW, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thing/thingname") &&
                    status.get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                shadowDeploymentCDL.countDown();
            }
            return true;
        }, "dummyValueShadow");


        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("thinggroup/firstGroup", "thinggroup/secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json")
                .toURI(), "thinggroup/firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSomeService.json")
                .toURI(), "thing/thingname", Deployment.DeploymentType.SHADOW);
        assertTrue(shadowDeploymentCDL.await(10, TimeUnit.SECONDS));

        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(new HashSet<>(Arrays.asList("thinggroup/secondGroup"))));
        submitSampleJobDocument(DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithYellowSignal.json")
                .toURI(), "thinggroup/secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);

        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(Arrays.asList("thinggroup/secondGroup","thing/thingname")));
        assertFalse(groupNames.contains("thinggroup/firstGroup"));

        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.find("YellowSignal", "thinggroup/secondGroup"));
        assertNotNull(componentsToGroupTopic.find("SomeService", "thing/thingname"));

    }

    @Test
    void GIVEN_device_offline_receives_local_deployment_THEN_no_components_deployed_earlier_are_removed(
            ExtensionContext context) throws Exception {
        String deviceOfflineErrorMsg = "Device is offline, failed to get thing group hierarchy";
        ignoreExceptionWithMessage(context, deviceOfflineErrorMsg);
        CountDownLatch firstGroupCDL = new CountDownLatch(1);
        CountDownLatch secondGroupCDL = new CountDownLatch(1);
        CountDownLatch shadowDeploymentCDL = new CountDownLatch(1);
        CountDownLatch localDeploymentCDL = new CountDownLatch(1);

        DeploymentStatusKeeper deploymentStatusKeeper = kernel.getContext().get(DeploymentStatusKeeper.class);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thinggroup/firstGroup") && status
                    .get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                firstGroupCDL.countDown();

            }
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thinggroup/secondGroup") && status
                    .get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                secondGroupCDL.countDown();

            }
            return true;
        }, "dummyValueIotJobs");

        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.SHADOW, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals("thing/thingname") && status.get(DEPLOYMENT_STATUS_KEY_NAME)
                    .equals("SUCCEEDED")) {
                shadowDeploymentCDL.countDown();
            }
            return true;
        }, "dummyValueShadow");

        String localDeploymentRequestId = UUID.randomUUID().toString();
        deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.LOCAL, (status) -> {
            if (status.get(DEPLOYMENT_ID_KEY_NAME).equals(localDeploymentRequestId) && status
                    .get(DEPLOYMENT_STATUS_KEY_NAME).equals("SUCCEEDED")) {
                localDeploymentCDL.countDown();
            }
            return true;
        }, "dummyValueLocal");


        when(thingGroupHelper.listThingGroupsForDevice(anyInt())).thenReturn(
                Optional.of(new HashSet<>(Arrays.asList("thinggroup/firstGroup", "thinggroup/secondGroup"))));

        submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithRedSignalService.json").toURI(),
                "thinggroup/firstGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(firstGroupCDL.await(10, TimeUnit.SECONDS));

        submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithYellowSignal.json").toURI(),
                "thinggroup/secondGroup", Deployment.DeploymentType.IOT_JOBS);
        assertTrue(secondGroupCDL.await(10, TimeUnit.SECONDS));

        submitSampleJobDocument(
                DeploymentServiceIntegrationTest.class.getResource("FleetConfigWithSomeService.json").toURI(),
                "thing/thingname", Deployment.DeploymentType.SHADOW);
        assertTrue(shadowDeploymentCDL.await(10, TimeUnit.SECONDS));

        when(thingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenThrow(SdkClientException.builder().message(deviceOfflineErrorMsg).build());

        submitLocalDocument(new HashMap<String, String>() {{
            put("HelloWorld", "1.0.0");
            put("YellowSignal", "1.0.0");
        }}, "secondGroup", localDeploymentRequestId);
        assertTrue(localDeploymentCDL.await(10, TimeUnit.SECONDS));

        Topics groupToRootTopic = kernel.getConfig()
                .findTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS, GROUP_TO_ROOT_COMPONENTS_TOPICS);
        List<String> groupNames = new ArrayList<>();
        groupToRootTopic.forEach(node -> groupNames.add(node.getName()));
        assertTrue(groupNames.containsAll(
                Arrays.asList("thinggroup/firstGroup", "thinggroup/secondGroup", "thing/thingname")));

        Topics componentsToGroupTopic = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS, COMPONENTS_TO_GROUPS_TOPICS);

        assertEquals("thing/thingname", Coerce.toString(componentsToGroupTopic.find("SomeService", "thing/thingname")));
        assertEquals("thinggroup/firstGroup",
                Coerce.toString(componentsToGroupTopic.find("RedSignal", "thinggroup/firstGroup")));
        assertEquals("thinggroup/secondGroup",
                Coerce.toString(componentsToGroupTopic.find("YellowSignal", localDeploymentRequestId)));
        assertEquals("thinggroup/secondGroup",
                Coerce.toString(componentsToGroupTopic.find("HelloWorld", localDeploymentRequestId)));

    }

    private void submitSampleJobDocument(URI uri, String arn, Deployment.DeploymentType type) throws Exception {
        // need to copy after each deployment because component clean up happens after each deployment.
        copyRecipeAndArtifacts();
        Configuration deploymentConfiguration = OBJECT_MAPPER.readValue(new File(uri), Configuration.class);
        deploymentConfiguration.setCreationTimestamp(System.currentTimeMillis());
        deploymentConfiguration.setConfigurationArn(arn);
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(deploymentConfiguration), type, deploymentConfiguration.getConfigurationArn());
        deploymentQueue.offer(deployment);
    }

    private void submitLocalDocument(Map<String, String> components, String groupName, String requestId)
            throws Exception {
        copyRecipeAndArtifacts();
        LocalOverrideRequest request = LocalOverrideRequest.builder()
                .requestId(requestId)
                .componentsToMerge(components)
                .groupName(groupName)
                .requestTimestamp(System.currentTimeMillis())
                .build();
        Deployment deployment = new Deployment(OBJECT_MAPPER.writeValueAsString(request), Deployment.DeploymentType.LOCAL, request.getRequestId());
        deploymentQueue.offer(deployment);
    }

    private void copyRecipeAndArtifacts() throws IOException {
        // pre-load contents to package store
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
        copyFolderRecursively(localStoreContentPath.resolve("artifacts"), kernel.getNucleusPaths().artifactPath(),
                REPLACE_EXISTING);
    }

}
