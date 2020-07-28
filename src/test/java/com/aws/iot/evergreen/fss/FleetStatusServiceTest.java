/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.fss;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.DependencyType;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.deployment.DeploymentStatusKeeper;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.GlobalStateChangeListener;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.PublishRequest;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_ACCOUNT_ID;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.fss.FleetStatusService.FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
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
    private ArgumentCaptor<GlobalStateChangeListener> addGlobalStateChangeListenerArgumentCaptor;

    private ScheduledThreadPoolExecutor ses;

    @BeforeEach
    public void setup() {
        serviceFullName = "FleetStatusService";
        initializeMockedConfig();
        ses = new ScheduledThreadPoolExecutor(4);
    }

    @Test
    public void GIVEN_component_status_change_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws InterruptedException, TimeoutException, ExecutionException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topic accountIdTopic = Topic.of(context, DEVICE_PARAM_ACCOUNT_ID, "12345");
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        Topic awsRegionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "testRegion");
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, "testGroup", allGroupTopics);
        Topic pkgTopic1 = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.10");
        Topic groupTopic1 = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, "12");
        Map<String, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, pkgTopic1);
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, groupTopic1);
        Topics pkgTopics = Topics.of(context, "MockService", deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put("MockService", pkgTopics);
        allGroupTopics.children.put(GROUP_TO_ROOT_COMPONENTS_TOPICS, deploymentGroupTopics);
        Map<EvergreenService, DependencyType> evergreenServiceDependencyTypeMap = new HashMap<>();
        evergreenServiceDependencyTypeMap.put(mockEvergreenService2, DependencyType.HARD);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockEvergreenService2.getName()).thenReturn("MockService2");
        when(mockEvergreenService2.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService2.getState()).thenReturn(State.RUNNING);
        when(mockEvergreenService1.getDependencies()).thenReturn(evergreenServiceDependencyTypeMap);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockEvergreenService1);
        when(mockKernel.locate("MockService2")).thenReturn(mockEvergreenService2);
        when(mockKernel.orderedDependencies()).thenReturn(Arrays.asList(mockEvergreenService1, mockEvergreenService2));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockDeviceConfiguration.getAccountId()).thenReturn(accountIdTopic);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getAWSRegion()).thenReturn(awsRegionTopic);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
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

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals("testGroup", fleetStatusDetails.getThingGroups());
        assertEquals(OverAllStatus.HEALTHY, fleetStatusDetails.getOverAllStatus());
        assertEquals(2, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(1).getFleetConfigArn());
        assertEquals("MockService2", fleetStatusDetails.getComponentStatusDetails().get(1).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(1).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(1).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(1).getFleetConfigArn());
    }

    @Test
    public void GIVEN_component_status_changes_to_broken_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_unhealthy_state()
            throws InterruptedException, TimeoutException, ExecutionException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topic accountIdTopic = Topic.of(context, DEVICE_PARAM_ACCOUNT_ID, "12345");
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        Topic awsRegionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "testRegion");
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, "testGroup", allGroupTopics);
        Topic t = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.10");
        Topic t1 = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, "12");
        Map<String, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, t);
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, t1);
        Topics pkgTopics = Topics.of(context, "MockService", deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put("MockService", pkgTopics);
        allGroupTopics.children.put(GROUP_TO_ROOT_COMPONENTS_TOPICS, deploymentGroupTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.BROKEN);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockEvergreenService1);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockEvergreenService1));

        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockDeviceConfiguration.getAccountId()).thenReturn(accountIdTopic);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getAWSRegion()).thenReturn(awsRegionTopic);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
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

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals("testGroup", fleetStatusDetails.getThingGroups());
        assertEquals(OverAllStatus.UNHEALTHY, fleetStatusDetails.getOverAllStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.BROKEN, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }

    @Test
    public void GIVEN_component_status_change_WHEN_deployment_does_not_finish_THEN_No_MQTT_Sent_with_fss_data()
            throws InterruptedException, TimeoutException, ExecutionException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockEvergreenService1.getName()).thenReturn("MockService");

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
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

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }


    @Test
    public void GIVEN_auto_start_component_status_change_WHEN_deployment_finishes_THEN_No_MQTT_Sent_with_fss_data()
            throws InterruptedException, TimeoutException, ExecutionException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockEvergreenService1.isAutostart()).thenReturn(true);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
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

        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.SUCCEEDED.toString());
        consumerArgumentCaptor.getValue().apply(map);

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }

    @Test
    public void GIVEN_auto_start_component_WHEN_periodic_update_triggerd_THEN_No_MQTT_Sent_with_fss_data()
            throws InterruptedException, TimeoutException, ExecutionException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "3000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockEvergreenService1.isAutostart()).thenReturn(true);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockEvergreenService1));

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        Thread.sleep(5_000);
        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }


    @Test
    public void GIVEN_component_status_change_WHEN_periodic_update_triggered_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws InterruptedException, TimeoutException, ExecutionException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "3000");
        Topic accountIdTopic = Topic.of(context, DEVICE_PARAM_ACCOUNT_ID, "12345");
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        Topic awsRegionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "testRegion");
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, "testGroup", allGroupTopics);
        Topic t = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.10");
        Topic t1 = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, "12");
        Map<String, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, t);
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, t1);
        Topics pkgTopics = Topics.of(context, "MockService", deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put("MockService", pkgTopics);
        allGroupTopics.children.put(GROUP_TO_ROOT_COMPONENTS_TOPICS, deploymentGroupTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.RUNNING);
        when(mockEvergreenService1.getDependencies()).thenReturn(new ConcurrentHashMap<>());
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockEvergreenService1);
        when(mockKernel.orderedDependencies()).thenReturn(Collections.singleton(mockEvergreenService1));
        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockDeviceConfiguration.getAccountId()).thenReturn(accountIdTopic);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getAWSRegion()).thenReturn(awsRegionTopic);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        Thread.sleep(6_000);
        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals("testGroup", fleetStatusDetails.getThingGroups());
        assertEquals(OverAllStatus.HEALTHY, fleetStatusDetails.getOverAllStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }

    @Test
    public void GIVEN_component_removed_WHEN_deployment_finishes_THEN_MQTT_Sent_with_fss_data_with_overall_healthy_state()
            throws InterruptedException, TimeoutException, ExecutionException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Set<EvergreenService> evergreenServices = new HashSet<>();
        evergreenServices.add(mockEvergreenService1);

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
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
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

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals("", fleetStatusDetails.getThingGroups());
        assertEquals(OverAllStatus.HEALTHY, fleetStatusDetails.getOverAllStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.RUNNING, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }

    @Test
    public void GIVEN_after_deployment_WHEN_component_status_changes_to_broken_THEN_MQTT_Sent_with_fss_data_with_overall_unhealthy_state()
            throws InterruptedException, TimeoutException, ExecutionException, ServiceLoadException, IOException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "100000");
        Topic accountIdTopic = Topic.of(context, DEVICE_PARAM_ACCOUNT_ID, "12345");
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        Topic awsRegionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "testRegion");
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, "testGroup", allGroupTopics);
        Topic t = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.10");
        Topic t1 = Topic.of(context, GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, "12");
        Map<String, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, t);
        pkgDetails.put(GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY, t1);
        Topics pkgTopics = Topics.of(context, "MockService", deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put("MockService", pkgTopics);
        allGroupTopics.children.put(GROUP_TO_ROOT_COMPONENTS_TOPICS, deploymentGroupTopics);

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(mockEvergreenService1.getName()).thenReturn("MockService");
        when(mockEvergreenService1.getServiceConfig()).thenReturn(config);
        when(mockEvergreenService1.getState()).thenReturn(State.BROKEN);
        when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        when(mockKernel.locate("MockService")).thenReturn(mockEvergreenService1);

        when(mockDeploymentService.getConfig()).thenReturn(config);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        when(mockDeviceConfiguration.getAccountId()).thenReturn(accountIdTopic);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getAWSRegion()).thenReturn(awsRegionTopic);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the state of an EG service.
        addGlobalStateChangeListenerArgumentCaptor.getValue()
                .globalServiceStateChanged(mockEvergreenService1, State.INSTALLED, State.BROKEN);

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(1)).publish(publishRequestArgumentCaptor.capture());

        PublishRequest publishRequest = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, publishRequest.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", publishRequest.getTopic());
        ObjectMapper mapper = new ObjectMapper();
        FleetStatusDetails fleetStatusDetails = mapper.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
        assertEquals("1.0.0", fleetStatusDetails.getGgcVersion());
        assertEquals("testThing", fleetStatusDetails.getThing());
        assertEquals("testGroup", fleetStatusDetails.getThingGroups());
        assertEquals(OverAllStatus.UNHEALTHY, fleetStatusDetails.getOverAllStatus());
        assertEquals(1, fleetStatusDetails.getComponentStatusDetails().size());
        assertEquals("MockService", fleetStatusDetails.getComponentStatusDetails().get(0).getComponentName());
        assertNull(fleetStatusDetails.getComponentStatusDetails().get(0).getStatusDetails());
        assertEquals(State.BROKEN, fleetStatusDetails.getComponentStatusDetails().get(0).getState());
        assertEquals("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12", fleetStatusDetails.getComponentStatusDetails().get(0).getFleetConfigArn());
    }


    @Test
    public void GIVEN_during_deployment_WHEN_periodic_update_triggered_THEN_No_MQTT_Sent()
            throws InterruptedException, TimeoutException, ExecutionException {
        // Set up all the topics
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS, "3000");

        // Set up all the mocks
        when(mockDeploymentStatusKeeper.registerDeploymentStatusConsumer(any(), consumerArgumentCaptor.capture(), anyString())).thenReturn(true);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        doNothing().when(context).addGlobalStateChangeListener(addGlobalStateChangeListenerArgumentCaptor.capture());
        when(config.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS))
                .thenReturn(periodicUpdateIntervalMsTopic);

        // Create the fleet status service instance
        FleetStatusService fleetStatusService = new FleetStatusService(config, mockMqttClient, mockDeviceConfiguration,
                mockDeploymentStatusKeeper, mockKernel);
        fleetStatusService.startup();

        // Update the job status for an ongoing deployment to IN_PROGRESS.
        HashMap<String, Object> map = new HashMap<>();
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, JobStatus.IN_PROGRESS.toString());
        map.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, "testJob");
        consumerArgumentCaptor.getValue().apply(map);

        Thread.sleep(5_000);

        fleetStatusService.shutdown();

        // Verify that an MQTT message with the components' status is uploaded.
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
    }
}
