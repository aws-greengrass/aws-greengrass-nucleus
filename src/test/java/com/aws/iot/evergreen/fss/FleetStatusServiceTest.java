/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.fss;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.deployment.DeploymentStatusKeeper;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.impl.config.EvergreenLogConfig;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.PublishRequest;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Function;

import static com.aws.iot.evergreen.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.fss.FleetStatusService.FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class FleetStatusServiceTest extends EGServiceTestUtil {
    @Mock
    private MqttClient mockMqttClient;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Mock
    private DeploymentStatusKeeper mockDeploymentStatusKeeper;
    @Mock
    private Kernel mockKernel;
    @Mock
    private EvergreenService mockEvergreenService1;
    @Mock
    private EvergreenService mockEvergreenService2;
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

    private ScheduledThreadPoolExecutor ses;
    private FleetStatusService fleetStatusService;

    @BeforeEach
    public void setup() {
        serviceFullName = "FleetStatusService";
        initializeMockedConfig();
        ses = new ScheduledThreadPoolExecutor(4);
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        lenient().when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        lenient().when(mockEvergreenService2.getName()).thenReturn("MockService2");
        lenient().when(mockEvergreenService1.getName()).thenReturn("MockService");
        EvergreenLogConfig.getInstance().setLevel(Level.DEBUG);
    }

    @AfterEach
    public void cleanUp() {
        fleetStatusService.shutdown();
    }

    @Test
    public void GIVEN_component_status_change_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put("MockService", groupTopic1);
        groupsTopics2.children.put("MockService2", groupTopic1);
        allComponentToGroupsTopics.children.put("MockService", groupsTopics);
        allComponentToGroupsTopics.children.put("MockService2", groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockEvergreenService2.getName()).thenReturn("MockService2");
        when(mockEvergreenService2.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService2.getState()).thenReturn(State.RUNNING);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(Arrays.asList(mockEvergreenService1, mockEvergreenService2));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to SUCCEEDED.
        HashMap<String, Object> map = new HashMap<>();
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.RUNNING);
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService2, State.INSTALLED, State.RUNNING);

        // Update the job status for an ongoing deployment to SUCCEEDED.
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        Set<String> serviceNamesToCheck = new HashSet<>();
        serviceNamesToCheck.add("MockService");
        serviceNamesToCheck.add("MockService2");
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(2, fleetStatusDetails.getComponentStatusDetails().size());
        serviceNamesToCheck.remove(fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(1).getFleetConfigArn());
        serviceNamesToCheck.remove(fleetStatusDetails.getComponentStatusDetails().get(1).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(1).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(1).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(1).getFleetConfigArn());
        assertTrue(serviceNamesToCheck.isEmpty());
    }

    @Test
    public void GIVEN_component_status_changes_to_broken_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_unhealthy_state()
            throws ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put("MockService", groupTopic1);
        groupsTopics2.children.put("MockService2", groupTopic1);
        allComponentToGroupsTopics.children.put("MockService", groupsTopics);
        allComponentToGroupsTopics.children.put("MockService2", groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.BROKEN);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockEvergreenService1));

        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        HashMap<String, Object> map = new HashMap<>();
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.BROKEN);

        // Update the job status for an ongoing deployment to SUCCEEDED.
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.UNHEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.BROKEN, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }

    @Test
    public void GIVEN_component_status_change_WHEN_deployment_does_not_finish_THEN_No_MQTT_Sent_with_fss_data() {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.RUNNING);
        HashMap<String, Object> map = new HashMap<>();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    public void GIVEN_component_status_change_WHEN_MQTT_connection_interrupted_THEN_No_MQTT_Sent_with_fss_data() {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        //when(mockEvergreenService1.getName()).thenReturn("MockService");
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        HashMap<String, Object> map = new HashMap<>();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.RUNNING);

        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    public void GIVEN_component_status_change_WHEN_periodic_update_triggered_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws InterruptedException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "3000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put("MockService", groupTopic1);
        groupsTopics2.children.put("MockService2", groupTopic1);
        allComponentToGroupsTopics.children.put("MockService", groupsTopics);
        allComponentToGroupsTopics.children.put("MockService2", groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockEvergreenService1));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        Thread.sleep(5_000);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }

    @Test
    public void GIVEN_component_removed_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Set<EvergreenService> evergreenServices = new HashSet<>();
        evergreenServices.add(mockEvergreenService1);
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockDeploymentService.getName()).thenReturn("MockDeploymentService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockKernel.locate(anyString())).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singletonList(mockDeploymentService));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        HashMap<String, Object> map = new HashMap<>();
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.RUNNING);
        fleetStatusService.updateRemovedDependencies(evergreenServices);

        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());

        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }

    @Test
    public void GIVEN_after_deployment_WHEN_component_status_changes_to_broken_THEN_MQTT_Sent_with_fss_data_with_overall_unhealthy_state()
            throws ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put("MockService", groupTopic1);
        groupsTopics2.children.put("MockService2", groupTopic1);
        allComponentToGroupsTopics.children.put("MockService", groupsTopics);
        allComponentToGroupsTopics.children.put("MockService2", groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.BROKEN);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);

        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.BROKEN);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.UNHEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.BROKEN, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }


    @Test
    public void GIVEN_during_deployment_WHEN_periodic_update_triggered_THEN_No_MQTT_Sent() throws InterruptedException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "3000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        HashMap<String, Object> map = new HashMap<>();
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        Thread.sleep(5_000);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    public void GIVEN_MQTT_connection_interrupted_WHEN_connection_resumes_THEN_MQTT_Sent_with_event_triggered_fss_data()
            throws ServiceLoadException, IOException, InterruptedException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put("MockService", groupTopic1);
        groupsTopics2.children.put("MockService2", groupTopic1);
        allComponentToGroupsTopics.children.put("MockService", groupsTopics);
        allComponentToGroupsTopics.children.put("MockService2", groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockEvergreenService2.getName()).thenReturn("MockService2");
        when(mockEvergreenService2.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService2.getState()).thenReturn(State.RUNNING);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(Arrays.asList(mockEvergreenService1, mockEvergreenService2));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to SUCCEEDED.
        HashMap<String, Object> map = new HashMap<>();
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.RUNNING);
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService2, State.INSTALLED, State.RUNNING);

        // Update the job status for an ongoing deployment to SUCCEEDED.
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(false);

        Thread.sleep(300);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        Set<String> serviceNamesToCheck = new HashSet<>();
        serviceNamesToCheck.add("MockService");
        serviceNamesToCheck.add("MockService2");

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(2, fleetStatusDetails.getComponentStatusDetails().size());
        serviceNamesToCheck.remove(fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(1).getFleetConfigArn());
        serviceNamesToCheck.remove(fleetStatusDetails.getComponentStatusDetails().get(1).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(1).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(1).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(1).getFleetConfigArn());
        assertTrue(serviceNamesToCheck.isEmpty());
    }


    @Test
    public void GIVEN_MQTT_connection_interrupted_WHEN_connection_resumes_THEN_MQTT_Sent_with_periodic_triggered_fss_data()
            throws InterruptedException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "3000");
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupsTopics = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics groupsTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12",
                true);
        groupsTopics.children.put("MockService", groupTopic1);
        groupsTopics2.children.put("MockService2", groupTopic1);
        allComponentToGroupsTopics.children.put("MockService", groupsTopics);
        allComponentToGroupsTopics.children.put("MockService2", groupsTopics2);
        lenient().when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockEvergreenService1));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());

        // Create the fleet status service instance
        fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);

        Thread.sleep(4_000);

        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(false);

        Thread.sleep(2_000);

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }
}
