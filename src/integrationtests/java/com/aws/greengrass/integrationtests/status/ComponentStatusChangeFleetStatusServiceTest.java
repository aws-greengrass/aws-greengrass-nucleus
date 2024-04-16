/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.status;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.integrationtests.util.ConfigPlatformResolver;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.status.model.FleetStatusDetails;
import com.aws.greengrass.status.model.OverallStatus;
import com.aws.greengrass.status.model.Trigger;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.NoOpPathOwnershipHandler;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_SERVICE_TOPICS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class ComponentStatusChangeFleetStatusServiceTest extends BaseITCase {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static DeviceConfiguration deviceConfiguration;
    private static Kernel kernel;
    @Mock
    private MqttClient mqttClient;

    private AtomicReference<List<FleetStatusDetails>> fleetStatusDetailsList;

    private CountDownLatch statusChange;

    private final static String SERVICE_A = "ServiceA";
    private final static String SERVICE_B = "ServiceB";
    private final static String SERVICE_C = "ServiceC";

    @BeforeEach
    void setupKernel() throws Exception {
        fleetStatusDetailsList = new AtomicReference<>(new ArrayList<>());
        kernel = new Kernel();
        NoOpPathOwnershipHandler.register(kernel);
        kernel.getContext().put(MqttClient.class, mqttClient);

        when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
            Object argument = i.getArgument(0);
            PublishRequest publishRequest = (PublishRequest) argument;
            try {
                FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(publishRequest.getPayload(), FleetStatusDetails.class);
                if (fleetStatusDetails.getTrigger().equals(Trigger.COMPONENT_STATUS_CHANGE)) {
                    statusChange.countDown();
                }
                fleetStatusDetailsList.get().add(fleetStatusDetails);
            } catch (JsonMappingException ignored) {
            }
            return CompletableFuture.completedFuture(0);
        });
    }
    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }
    @Test
    void GIVEN_one_component_errored_and_recovered_THEN_fss_should_send_recovery_message() throws Exception {
        statusChange = new CountDownLatch(2);
        deviceConfiguration = new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine(), "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath", "certFilePath", "caFilePath",
                "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                ComponentStatusChangeFleetStatusServiceTest.class.getResource("fss_service_recovery.yaml"));
        kernel.launch();

        FleetStatusService fss = (FleetStatusService) kernel.locateIgnoreError(FLEET_STATUS_SERVICE_TOPICS);
        fss.setWaitBetweenPublishDisabled(true);

        assertTrue(statusChange.await(20, TimeUnit.SECONDS));

        // we expect a total of 3 messages; 1 nucleus launch and 2 component status change (error + recovery)
        assertEquals(3, fleetStatusDetailsList.get().size());

        // the first message should be nucleus launch
        FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(Trigger.NUCLEUS_LAUNCH, fleetStatusDetails.getTrigger());

        // the second message should be errored component
        fleetStatusDetails = fleetStatusDetailsList.get().get(1);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_A, State.ERRORED);

        // the third message should be component recovery
        fleetStatusDetails = fleetStatusDetailsList.get().get(2);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_A, State.RUNNING);
    }

    @Test
    void GIVEN_two_components_errored_and_recovered_THEN_fss_should_send_only_one_recovery_message() throws Exception {
        statusChange  = new CountDownLatch(3);
        deviceConfiguration = new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine(), "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath", "certFilePath", "caFilePath",
                "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                ComponentStatusChangeFleetStatusServiceTest.class.getResource("fss_service_5s_recovery.yaml"));
        kernel.launch();

        FleetStatusService fss = (FleetStatusService) kernel.locateIgnoreError(FLEET_STATUS_SERVICE_TOPICS);
        fss.setWaitBetweenPublishDisabled(true);
        //Increase this for windows testing
        assertTrue(statusChange.await(30, TimeUnit.SECONDS));
        // we expect a total of 4 messages, 1 Nucleus launch, 3 component status change includes:
        // 1 Errored from A, 1 Errored B, 1 recovery message for both
        assertEquals(4, fleetStatusDetailsList.get().size());

        // the first message should be nucleus launch
        FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(Trigger.NUCLEUS_LAUNCH, fleetStatusDetails.getTrigger());

        // the second and third message should be errored component
        fleetStatusDetails = fleetStatusDetailsList.get().get(1);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_B, State.ERRORED);

        fleetStatusDetails = fleetStatusDetailsList.get().get(2);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_A, State.ERRORED);

        // the fourth message should be component recovery
        fleetStatusDetails = fleetStatusDetailsList.get().get(3);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_A, State.RUNNING);
        componentStatusAssertion(fleetStatusDetails, SERVICE_B, State.RUNNING);
    }
    @Test
    void GIVEN_three_components_errored_and_recovered_THEN_fss_should_send_only_one_recovery_message_and_one_errored_message_should_contain_recovery_message() throws Exception {
        statusChange  = new CountDownLatch(4);
        deviceConfiguration = new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine(), "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath", "certFilePath", "caFilePath",
                "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                ComponentStatusChangeFleetStatusServiceTest.class.getResource("fss_service_10s_recovery.yaml"));
        kernel.launch();

        FleetStatusService fss = (FleetStatusService) kernel.locateIgnoreError(FLEET_STATUS_SERVICE_TOPICS);
        fss.setWaitBetweenPublishDisabled(true);
        //Increase this for windows testing
        assertTrue(statusChange.await(30, TimeUnit.SECONDS));
        // we expect a total of 5 messages, 1 Nucleus launch, 4 component status change includes:
        // 1 Errored from A with B recovered, 1 Errored B, 1 Errored C, 1 recovery message for the rest of non recovery ones
        assertEquals(5, fleetStatusDetailsList.get().size());

        // the first message should be nucleus launch
        FleetStatusDetails fleetStatusDetails = fleetStatusDetailsList.get().get(0);
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
        assertEquals(Trigger.NUCLEUS_LAUNCH, fleetStatusDetails.getTrigger());

        // the second should be errored component C
        fleetStatusDetails = fleetStatusDetailsList.get().get(1);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_C, State.ERRORED);

        // the 3rd message should be errored component B
        fleetStatusDetails = fleetStatusDetailsList.get().get(2);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_B, State.ERRORED);

        // the 4th message should be errrored component A with recovery of B
        fleetStatusDetails = fleetStatusDetailsList.get().get(3);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_A, State.ERRORED);
        assertTrue(fleetStatusDetails.getComponentDetails().stream()
                .anyMatch(c -> "ServiceB".equals(c.getComponentName()) && !State.ERRORED.equals(c.getState())));

        // the 5th message should be rest component recovery
        fleetStatusDetails = fleetStatusDetailsList.get().get(4);
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fleetStatusDetails.getTrigger());
        componentStatusAssertion(fleetStatusDetails, SERVICE_C, State.RUNNING);
        componentStatusAssertion(fleetStatusDetails, SERVICE_A, State.RUNNING);
    }
    private void componentStatusAssertion(FleetStatusDetails fleetStatusDetails, String componentName, State state) {
        assertTrue(fleetStatusDetails.getComponentDetails().stream()
                .anyMatch(c -> componentName.equals(c.getComponentName()) && state.equals(c.getState())));
    }
}
