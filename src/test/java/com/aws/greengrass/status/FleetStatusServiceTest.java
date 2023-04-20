/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status;

import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ComponentStatusCode;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.model.ComponentDetails;
import com.aws.greengrass.status.model.ComponentStatusDetails;
import com.aws.greengrass.status.model.FleetStatusDetails;
import com.aws.greengrass.status.model.MessageType;
import com.aws.greengrass.status.model.OverallStatus;
import com.aws.greengrass.status.model.Trigger;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.collection.IsEmptyCollection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_TYPE_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.GG_DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.FLEET_STATUS_CONFIG_TOPICS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.IOT_JOBS;
import static com.aws.greengrass.status.FleetStatusService.DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_LAST_PERIODIC_UPDATE_TIME_TOPIC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SEQUENCE_NUMBER_TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class FleetStatusServiceTest extends GGServiceTestUtil {
    @Mock
    private MqttClient mockMqttClient;
    @Mock
    private DeploymentStatusKeeper mockDeploymentStatusKeeper;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Mock
    private Kernel mockKernel;
    @Mock
    private GreengrassService mockGreengrassService1;
    @Mock
    private GreengrassService mockGreengrassService2;
    @Mock
    private DeploymentService mockDeploymentService;
    @Captor
    private ArgumentCaptor<Function<Map<String, Object>, Boolean>> consumerArgumentCaptor;
    @Captor
    private ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor;
    @Captor
    private ArgumentCaptor<MqttClientConnectionEvents> mqttClientConnectionEventsArgumentCaptor;
    @Captor
    private ArgumentCaptor<GlobalStateChangeListener> addGlobalStateChangeListenerArgumentCaptor;

    // use it for disabling periodic updates
    @Mock
    private ScheduledThreadPoolExecutor mockSes;

    private ScheduledThreadPoolExecutor ses;
    private FleetStatusService fleetStatusService;
    private static final String VERSION = "2.0.0";
    private static final ComponentStatusDetails TEST_BROKEN_COMPONENT_STATUS_DETAILS =
            ComponentStatusDetails.builder()
                    .statusCodes(Arrays.asList(ComponentStatusCode.RUN_ERROR.name()))
                    .statusReason(ComponentStatusCode.RUN_ERROR.getDescription())
                    .build();

    @BeforeEach
    void setup() {
        serviceFullName = "FleetStatusService";
        initializeMockedConfig();
        ses = new ScheduledThreadPoolExecutor(4);
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        lenient().when(mockGreengrassService2.getName()).thenReturn("MockService2");
        lenient().when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        lenient().when(mockDeviceConfiguration.getNucleusVersion()).thenReturn(VERSION);
        Topic sequenceNumberTopic = Topic.of(context, FLEET_STATUS_SEQUENCE_NUMBER_TOPIC, "0");
        lenient().when(config.lookup(FLEET_STATUS_SEQUENCE_NUMBER_TOPIC)).thenReturn(sequenceNumberTopic);
        Topic lastPeriodicUpdateTime = Topic.of(context, FLEET_STATUS_LAST_PERIODIC_UPDATE_TIME_TOPIC, Instant.now().toEpochMilli());
        lenient().when(config.lookup(FLEET_STATUS_LAST_PERIODIC_UPDATE_TIME_TOPIC)).thenReturn(lastPeriodicUpdateTime);
        lenient().when(mockMqttClient.publish(any(PublishRequest.class))).thenReturn(CompletableFuture.completedFuture(0));
    }

    @AfterEach
    void cleanUp() {
        ses.shutdownNow();
        fleetStatusService.shutdown();
        fleetStatusService.clearServiceSet();
    }

    void assertServiceIsRootOrNot(ComponentDetails componentDetails) {
        if (componentDetails.getComponentName().equals("MockService")) {
            assertTrue(componentDetails.isRoot());
        } else {
            assertFalse(componentDetails.isRoot());
        }
    }

    @Test
    void GIVEN_component_status_change_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService1.inState(State.ERRORED)).thenReturn(false);
        when(mockGreengrassService2.getName()).thenReturn("MockService2");
        when(mockGreengrassService2.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService2.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService2.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService2.inState(State.ERRORED)).thenReturn(false);
        when(mockGreengrassService2.isBuiltin()).thenReturn(true);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);
        when(mockKernel.locate("MockService2")).thenReturn(mockGreengrassService2);
        when(mockKernel.orderedDependencies()).thenReturn(Arrays.asList(mockGreengrassService1, mockGreengrassService2));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        when(mockDeploymentService.isComponentRoot("MockService")).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);

        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to SUCCEEDED.
        Map<String, Object> map = new HashMap<>();
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        map.put(DEPLOYMENT_TYPE_KEY_NAME, IOT_JOBS);
        Map<String, String> statusDetails = new HashMap<>();
        statusDetails.put(DEPLOYMENT_DETAILED_STATUS_KEY, DeploymentResult.DeploymentStatus.SUCCESSFUL.toString());
        map.put(DEPLOYMENT_STATUS_DETAILS_KEY_NAME, statusDetails);
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.RUNNING);
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService2, State.INSTALLED, State.RUNNING);

        // Update the job status for an ongoing deployment to SUCCEEDED.
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
        Set<String> serviceNamesToCheck = new HashSet<>();
        serviceNamesToCheck.add("MockService");
        serviceNamesToCheck.add("MockService2");
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
        assertEquals(Trigger.THING_GROUP_DEPLOYMENT, fleetStatusDetails.getTrigger());
        assertNull(fleetStatusDetails.getChunkInfo());
        assertEquals(JobStatus.SUCCEEDED.toString(), fleetStatusDetails.getDeploymentInformation().getStatus());
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL.toString(),
                fleetStatusDetails.getDeploymentInformation().getStatusDetails().getDetailedStatus());
        assertNull(fleetStatusDetails.getDeploymentInformation().getStatusDetails().getFailureCause());
        assertNull(fleetStatusDetails.getDeploymentInformation().getUnchangedRootComponents());
        assertEquals(2, fleetStatusDetails.getComponentDetails().size());
        assertServiceIsRootOrNot(fleetStatusDetails.getComponentDetails().get(0));
        serviceNamesToCheck.remove(fleetStatusDetails.getComponentDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentDetails().get(0).getComponentStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentDetails().get(0).getState());
        assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"), fleetStatusDetails.getComponentDetails().get(1).getFleetConfigArns());
        assertServiceIsRootOrNot(fleetStatusDetails.getComponentDetails().get(1));
        serviceNamesToCheck.remove(fleetStatusDetails.getComponentDetails().get(1).getComponentName());
        assertNull(fleetStatusDetails.getComponentDetails().get(1).getComponentStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentDetails().get(1).getState());
        assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"), fleetStatusDetails.getComponentDetails().get(1).getFleetConfigArns());
        assertThat(serviceNamesToCheck, is(IsEmptyCollection.empty()));
    }

    @Test
    void GIVEN_component_status_changes_to_broken_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_unhealthy_state()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getStatusDetails()).thenReturn(TEST_BROKEN_COMPONENT_STATUS_DETAILS);
        when(mockGreengrassService1.getState()).thenReturn(State.BROKEN);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(true);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockGreengrassService1));

        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        Map<String, Object> map = new HashMap<>();
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        map.put(DEPLOYMENT_TYPE_KEY_NAME, IOT_JOBS);
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.BROKEN);

        // Update the job status for service broken after deployment
        String failureCauseMessage = "Service in broken state after deployment";
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.FAILED.toString());
        Map<String, String> statusDetails = new HashMap<>();
        statusDetails.put(DEPLOYMENT_DETAILED_STATUS_KEY, DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED.toString());
        statusDetails.put(DEPLOYMENT_FAILURE_CAUSE_KEY, failureCauseMessage);
        map.put(DEPLOYMENT_STATUS_DETAILS_KEY_NAME, statusDetails);
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.UNHEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
        assertEquals(Trigger.THING_GROUP_DEPLOYMENT, fleetStatusDetails.getTrigger());
        assertNull(fleetStatusDetails.getChunkInfo());
        assertEquals(JobStatus.FAILED.toString(), fleetStatusDetails.getDeploymentInformation().getStatus());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED.toString(),
                fleetStatusDetails.getDeploymentInformation().getStatusDetails().getDetailedStatus());
        assertEquals(failureCauseMessage,
                fleetStatusDetails.getDeploymentInformation().getStatusDetails().getFailureCause());
        assertNull(fleetStatusDetails.getDeploymentInformation().getUnchangedRootComponents());
        assertEquals(1, fleetStatusDetails.getComponentDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentDetails().get(0).getComponentName());
        assertEquals(TEST_BROKEN_COMPONENT_STATUS_DETAILS, fleetStatusDetails.getComponentDetails().get(0).getComponentStatusDetails());
        assertEquals(State.BROKEN, fleetStatusDetails.getComponentDetails().get(0).getState());
        assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"), fleetStatusDetails.getComponentDetails().get(0).getFleetConfigArns());
    }

    @Test
    void GIVEN_component_status_change_WHEN_deployment_does_not_finish_THEN_No_MQTT_Sent_with_fss_data() throws InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.RUNNING);

        Map<String, Object> map = new HashMap<>();
        // Update the job status for an ongoing deployment to IN_PROGRESS.
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    void GIVEN_component_status_change_WHEN_MQTT_connection_interrupted_THEN_No_MQTT_Sent_with_fss_data() throws InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.RUNNING);

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    void GIVEN_component_status_change_WHEN_periodic_update_triggered_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws InterruptedException, ServiceLoadException, IOException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("3");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService1.inState(State.ERRORED)).thenReturn(false);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockGreengrassService1));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS(3);
        fleetStatusService.startup();

        TimeUnit.SECONDS.sleep(5);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, atLeast(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(MessageType.COMPLETE, fleetStatusDetails.getMessageType());
        assertEquals(Trigger.CADENCE, fleetStatusDetails.getTrigger());
        assertNull(fleetStatusDetails.getChunkInfo());
        assertEquals(1, fleetStatusDetails.getComponentDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentDetails().get(0).getComponentStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentDetails().get(0).getState());
        assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"), fleetStatusDetails.getComponentDetails().get(0).getFleetConfigArns());
    }

    @Test
    void GIVEN_periodic_update_less_than_default_WHEN_config_read_THEN_sets_publish_interval_to_default()
            throws InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("3");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        assertEquals(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC, fleetStatusService.getPeriodicPublishIntervalSec());
    }

    @Test
    void GIVEN_periodic_update_more_than_default_WHEN_config_read_THEN_sets_publish_interval_to_correct_value()
            throws InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("90000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        assertEquals(90000, fleetStatusService.getPeriodicPublishIntervalSec());
    }

    @Test
    void GIVEN_component_removed_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService1.inState(State.ERRORED)).thenReturn(false);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singletonList(mockDeploymentService));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        Map<String, Object> map = new HashMap<>();
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        map.put(DEPLOYMENT_TYPE_KEY_NAME, IOT_JOBS);
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.RUNNING);
        fleetStatusService.addServicesToPreviouslyKnownServicesList(Collections.singletonList(
                mockGreengrassService1), Instant.MIN);

        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.SUCCEEDED.toString());
        Map<String, String> statusDetails = new HashMap<>();
        statusDetails.put(DEPLOYMENT_DETAILED_STATUS_KEY, DeploymentResult.DeploymentStatus.SUCCESSFUL.toString());
        map.put(DEPLOYMENT_STATUS_DETAILS_KEY_NAME, statusDetails);

        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
        assertEquals(Trigger.THING_GROUP_DEPLOYMENT, fleetStatusDetails.getTrigger());
        assertNull(fleetStatusDetails.getChunkInfo());
        assertEquals(JobStatus.SUCCEEDED.toString(), fleetStatusDetails.getDeploymentInformation().getStatus());
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL.toString(),
                fleetStatusDetails.getDeploymentInformation().getStatusDetails().getDetailedStatus());
        assertNull(fleetStatusDetails.getDeploymentInformation().getStatusDetails().getFailureCause());
        assertNull(fleetStatusDetails.getDeploymentInformation().getUnchangedRootComponents());

        fleetStatusDetails.getComponentDetails().forEach(System.out::println);
        assertEquals(1, fleetStatusDetails.getComponentDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentDetails().get(0).getComponentStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentDetails().get(0).getState());
        assertThat(fleetStatusDetails.getComponentDetails().get(0).getFleetConfigArns(), is(IsEmptyCollection.empty()));
    }

    @Test
    void GIVEN_after_deployment_WHEN_component_status_changes_to_broken_THEN_MQTT_Sent_with_fss_data_with_overall_unhealthy_state()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getStatusDetails()).thenReturn(TEST_BROKEN_COMPONENT_STATUS_DETAILS);
        when(mockGreengrassService1.getState()).thenReturn(State.BROKEN);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(true);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);

        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.BROKEN);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.UNHEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
        assertEquals(Trigger.BROKEN_COMPONENT, fleetStatusDetails.getTrigger());
        assertNull(fleetStatusDetails.getChunkInfo());
        assertEquals(1, fleetStatusDetails.getComponentDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentDetails().get(0).getComponentName());
        assertEquals(TEST_BROKEN_COMPONENT_STATUS_DETAILS, fleetStatusDetails.getComponentDetails().get(0).getComponentStatusDetails());
        assertEquals(State.BROKEN, fleetStatusDetails.getComponentDetails().get(0).getState());
        assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"), fleetStatusDetails.getComponentDetails().get(0).getFleetConfigArns());
    }


    @Test
    void GIVEN_during_deployment_WHEN_periodic_update_triggered_THEN_No_MQTT_Sent() throws InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("3000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        Map<String, Object> map = new HashMap<>();
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        TimeUnit.SECONDS.sleep(5);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    void GIVEN_deployment_completes_while_MQTT_connection_interrupted_WHEN_connection_resumes_THEN_MQTT_Sent_with_both_deployment_and_reconnect_event()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService1.inState(State.ERRORED)).thenReturn(false);
        when(mockGreengrassService2.getName()).thenReturn("MockService2");
        when(mockGreengrassService2.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService2.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService2.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService2.inState(State.ERRORED)).thenReturn(false);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);
        when(mockKernel.locate("MockService2")).thenReturn(mockGreengrassService2);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);

        when(mockKernel.orderedDependencies()).thenReturn(Arrays.asList(mockGreengrassService1, mockGreengrassService2));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        Map<String, Object> map = new HashMap<>();
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        map.put(GG_DEPLOYMENT_ID_KEY_NAME, "testJobUuid");
        map.put(DEPLOYMENT_TYPE_KEY_NAME, "IOT_JOBS");
        consumerArgumentCaptor.getValue().apply(map);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService1, State.INSTALLED, State.RUNNING);

        // Update the job status for an ongoing deployment to SUCCEEDED.
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Update another state change after deployment completes
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockGreengrassService2, State.INSTALLED, State.RUNNING);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(false);

        Thread.sleep(300);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, atLeast(2)).publish(publishRequestArgumentCaptor.capture());

        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < publishRequests.size(); i++) {
            PublishRequest publishRequest = publishRequests.get(i);
            assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
            assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
            FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
            assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
            assertEquals("testThing", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertNull(fleetStatusDetails.getChunkInfo());
            assertEquals(1, fleetStatusDetails.getComponentDetails().size());
            ComponentDetails componentDetails = fleetStatusDetails.getComponentDetails().get(0);
            if (i == 0) {
                assertEquals(Trigger.THING_GROUP_DEPLOYMENT, fleetStatusDetails.getTrigger());
                assertEquals("MockService", componentDetails.getComponentName());
                assertEquals("testJobUuid", fleetStatusDetails.getDeploymentInformation().getDeploymentId());
                assertNull(fleetStatusDetails.getDeploymentInformation().getUnchangedRootComponents());
                assertNull(componentDetails.getComponentStatusDetails());
                assertEquals(State.RUNNING, componentDetails.getState());
                assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"),
                        componentDetails.getFleetConfigArns());
            } else {
                assertEquals(Trigger.RECONNECT, fleetStatusDetails.getTrigger());
                assertEquals("MockService2", componentDetails.getComponentName());
                assertNull(componentDetails.getComponentStatusDetails());
            }
        }
    }

    @Test
    void GIVEN_flaky_MQTT_connection_WHEN_no_component_state_change_THEN_MQTT_sent_only_once()
            throws InterruptedException, IOException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(mockSes);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // disconnect and reconnect 3 times
        for (int i = 0; i < 3; i++) {
            mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);
            TimeUnit.MILLISECONDS.sleep(100);

            mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(false);
            TimeUnit.MILLISECONDS.sleep(100);
        }

        // Verify that MQTT only receives FSS update request once.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(1, publishRequests.size());
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequests.get(0).getPayload(),
                FleetStatusDetails.class);
        assertEquals(Trigger.RECONNECT, fleetStatusDetails.getTrigger());
    }

    @Test
    void GIVEN_MQTT_connection_interrupted_WHEN_connection_resumes_THEN_MQTT_Sent_with_periodic_triggered_fss_data()
            throws InterruptedException, ServiceLoadException, IOException {
        // Set up all the topics
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("3");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        groupsTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), groupsTopics);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockGreengrassService1.getName()).thenReturn("MockService");
        when(mockGreengrassService1.getServiceConfig()).thenReturn(config);
        when(mockGreengrassService1.getState()).thenReturn(State.RUNNING);
        when(mockGreengrassService1.inState(State.BROKEN)).thenReturn(false);
        when(mockGreengrassService1.inState(State.ERRORED)).thenReturn(false);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockGreengrassService1);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockGreengrassService1));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = createFSS(3);
        fleetStatusService.startup();
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);

        TimeUnit.SECONDS.sleep(4);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(false);

        TimeUnit.SECONDS.sleep(5);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, atLeast(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(MessageType.COMPLETE, fleetStatusDetails.getMessageType());
        assertEquals(Trigger.CADENCE, fleetStatusDetails.getTrigger());
        assertNull(fleetStatusDetails.getChunkInfo());
        assertEquals(1, fleetStatusDetails.getComponentDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentDetails().get(0).getComponentStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentDetails().get(0).getState());
        assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"), fleetStatusDetails.getComponentDetails().get(0).getFleetConfigArns());
    }

    @Test
    void GIVEN_large_num_component_status_change_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        int numServices = 1500;
        Topics statusConfigTopics = Topics.of(context, FLEET_STATUS_CONFIG_TOPICS, null);
        statusConfigTopics.createLeafChild(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC).withValue("10000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);

        List<GreengrassService> greengrassServices = new ArrayList<>();
        Set<String> serviceNamesToCheck = new HashSet<>();
        for (int i = 0; i < numServices; i++) {
            String serviceName = String.format("MockService-%s", i);
            Topics groupsTopics = Topics.of(context, serviceName, allComponentToGroupsTopics);
            groupsTopics.children.put(new CaseInsensitiveString(serviceName), groupTopic1);
            allComponentToGroupsTopics.children.put(new CaseInsensitiveString(serviceName), groupsTopics);
            GreengrassService greengrassService = mock(GreengrassService.class);
            when(greengrassService.getName()).thenReturn(serviceName);
            when(greengrassService.getState()).thenReturn(State.RUNNING);
            when(greengrassService.inState(State.BROKEN)).thenReturn(false);
            when(greengrassService.inState(State.ERRORED)).thenReturn(false);
            when(greengrassService.getServiceConfig()).thenReturn(config);
            greengrassServices.add(greengrassService);
            serviceNamesToCheck.add(serviceName);
            when(mockKernel.locate(serviceName)).thenReturn(greengrassService);
        }
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(greengrassServices);
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(mockDeviceConfiguration.getStatusConfigurationTopics()).thenReturn(statusConfigTopics);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = createFSS();
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to SUCCEEDED.
        Map<String, Object> map = new HashMap<>();
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.IN_PROGRESS.toString());
        map.put(DEPLOYMENT_ID_KEY_NAME, "testJob");
        map.put(DEPLOYMENT_TYPE_KEY_NAME, IOT_JOBS);
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        for (int i = 0; i < numServices; i++) {
            addGlobalStateChangeListenerArgumentCaptor.getValue()
                    .globalServiceStateChanged(greengrassServices.get(i), State.INSTALLED, State.RUNNING);
        }

        // Update the job status for an ongoing deployment to SUCCEEDED.
        map.put(DEPLOYMENT_STATUS_KEY_NAME, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(3)).publish(publishRequestArgumentCaptor.capture());
        List<PublishRequest> publishRequests = publishRequestArgumentCaptor.getAllValues();
        ObjectMapper mapper = new ObjectMapper();
        for (int i = 0; i < publishRequests.size(); i++) {
            PublishRequest publishRequest = publishRequests.get(i);
            assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
            assertEquals("$aws/things/testThing/greengrassv2/health/json", publishRequest.getTopic());
            FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
            assertEquals(VERSION, fleetStatusDetails.getGgcVersion());
            assertEquals("testThing", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertEquals(MessageType.PARTIAL, fleetStatusDetails.getMessageType());
            assertEquals(Trigger.THING_GROUP_DEPLOYMENT, fleetStatusDetails.getTrigger());
            assertEquals(i + 1, fleetStatusDetails.getChunkInfo().getChunkId());
            assertEquals(publishRequests.size(), fleetStatusDetails.getChunkInfo().getTotalChunks());
            for (ComponentDetails componentDetails : fleetStatusDetails.getComponentDetails()) {
                serviceNamesToCheck.remove(componentDetails.getComponentName());
                assertNull(componentDetails.getComponentStatusDetails());
                assertEquals(State.RUNNING, componentDetails.getState());
                assertEquals(Collections.singletonList("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"),
                        componentDetails.getFleetConfigArns());
            }
        }
        assertThat(serviceNamesToCheck, is(IsEmptyCollection.empty()));
    }

    private FleetStatusService createFSS() {
        PlatformResolver platformResolver = new PlatformResolver(null);
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient,
                mockDeploymentStatusKeeper, mockKernel, mockDeviceConfiguration, platformResolver);
        fleetStatusService.postInject();
        return fleetStatusService;
    }

    private FleetStatusService createFSS(int periodicUpdateIntervalSec) {
        PlatformResolver platformResolver = new PlatformResolver(null);
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient,
                mockDeploymentStatusKeeper, mockKernel, mockDeviceConfiguration, platformResolver,
                periodicUpdateIntervalSec);
        fleetStatusService.postInject();
        return fleetStatusService;
    }
}
