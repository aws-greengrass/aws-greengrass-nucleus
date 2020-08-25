/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.fss;

import com.amazonaws.arn.Arn;
import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.fss.ComponentStatusDetails;
import com.aws.iot.evergreen.fss.FleetStatusDetails;
import com.aws.iot.evergreen.fss.FleetStatusService;
import com.aws.iot.evergreen.fss.OverallStatus;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.SubscribeRequest;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.kernel.KernelVersion.KERNEL_VERSION;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
@Tag("E2E")
public class FleetStatusServiceTest extends BaseE2ETestCase {
    private static final ObjectMapper DESERIALIZER = new ObjectMapper();
    private static final String FLEET_STATUS_ARN_SERVICE = "greengrass";
    private static final String FLEET_STATUS_ARN_PARTITION = "aws";

    @AfterEach
    void afterEach() {
        try {
            if (kernel != null) {
                kernel.shutdown();
            }
        } finally {
            // Cleanup all IoT thing resources we created
            cleanup();
        }
    }

    @BeforeEach
    void launchKernel() throws Exception {
        initKernel();
        kernel.launch();

        // TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        TimeUnit.SECONDS.sleep(10);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_finishes_THEN_fss_data_is_uploaded() throws Exception {
        MqttClient client = kernel.getContext().get(MqttClient.class);

        CountDownLatch cdl = new CountDownLatch(2);
        AtomicReference<List<MqttMessage>> mqttMessagesList = new AtomicReference<>();
        mqttMessagesList.set(new ArrayList<>());
        // TODO: Make the publish topic configurable?
        client.subscribe(SubscribeRequest.builder()
                .topic(FleetStatusService.DEFAULT_FLEET_STATUS_SERVICE_PUBLISH_TOPIC.replace("{thingName}", thingInfo.getThingName()))
                .callback((m) -> {
                    cdl.countDown();
                    mqttMessagesList.get().add(m);
                }).build());

        // First Deployment to have some services running in Kernel which can be removed later
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        String someServiceName = getCloudDeployedComponent("SomeService").getName();
        Set<String> componentNames = new HashSet<>();
        componentNames.add(getCloudDeployedComponent("Mosquitto").getName());
        componentNames.add(someServiceName);
        componentNames.add(getCloudDeployedComponent("CustomerApp").getName());
        componentNames.add(getCloudDeployedComponent("GreenSignal").getName());
        kernel.orderedDependencies().forEach(evergreenService -> {
            if(evergreenService.isBuiltin() || evergreenService.getName().equals("main")) {
                componentNames.add(evergreenService.getName());
            }
        });

        // Second deployment to remove some services deployed previously
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("SomeService").getState());

        assertTrue(cdl.await(1, TimeUnit.MINUTES), "All messages published and received");
        assertEquals(2, mqttMessagesList.get().size());
        String accountId = Coerce.toString(Arn.fromString(thingInfo.getThingArn()).getAccountId());
        String region = Coerce.toString(Arn.fromString(thingInfo.getThingArn()).getRegion());
        String resource = String.format("%s%s", "thinggroup/", thingGroupName);

        // Check the MQTT messages.
        // The first MQTT message should have all the services whose status changed during the first deployment.
        // This will include the system and user components.
        MqttMessage receivedMqttMessage1 = mqttMessagesList.get().get(0);
        assertNotNull(receivedMqttMessage1.getPayload());
        FleetStatusDetails fleetStatusDetails1 = DESERIALIZER.readValue(receivedMqttMessage1.getPayload(), FleetStatusDetails.class);
        assertEquals(thingInfo.getThingName(), fleetStatusDetails1.getThing());
        assertEquals(KERNEL_VERSION, fleetStatusDetails1.getGgcVersion());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails1.getOverallStatus());
        assertEquals(0, fleetStatusDetails1.getSequenceNumber());
        fleetStatusDetails1.getComponentStatusDetails().forEach(componentStatusDetails ->
                componentNames.remove(componentStatusDetails.getComponentName()));
        assertTrue(componentNames.isEmpty());
        fleetStatusDetails1.getComponentStatusDetails().forEach(componentStatusDetails -> {
            assertEquals(1, componentStatusDetails.getFleetConfigArns().size());
            Arn componentArn = Arn.fromString(componentStatusDetails.getFleetConfigArns().get(0));
            assertEquals(region, componentArn.getRegion());
            assertEquals(accountId, componentArn.getAccountId());
            assertEquals(FLEET_STATUS_ARN_PARTITION, componentArn.getPartition());
            assertEquals(FLEET_STATUS_ARN_SERVICE, componentArn.getService());
            assertEquals(resource, componentArn.getResource().getResource());
            // This will handle the case where tests are running in parallel and the deployment qualifier
            // is not consistent.
            assertNotNull(componentArn.getResource().getQualifier());
        });

        // The second MQTT message should contain only one component information which was removed during the second
        // deployment.
        // The configuration arns for that component should be empty to indicate that it was removed from all groups.
        MqttMessage receivedMqttMessage2 = mqttMessagesList.get().get(1);
        assertNotNull(receivedMqttMessage2.getPayload());
        FleetStatusDetails fleetStatusDetails2 = DESERIALIZER.readValue(receivedMqttMessage2.getPayload(), FleetStatusDetails.class);
        assertEquals(thingInfo.getThingName(), fleetStatusDetails2.getThing());
        assertEquals(KERNEL_VERSION, fleetStatusDetails2.getGgcVersion());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails2.getOverallStatus());
        assertEquals(1, fleetStatusDetails2.getSequenceNumber());
        assertEquals(1, fleetStatusDetails2.getComponentStatusDetails().size());
        assertThat(fleetStatusDetails2.getComponentStatusDetails().stream().map(ComponentStatusDetails::getComponentName).collect(Collectors.toList()),
                containsInAnyOrder(someServiceName));
        assertThat("Component was removed from the kernel.", fleetStatusDetails2.getComponentStatusDetails().get(0).getFleetConfigArns().isEmpty());
        assertEquals(someServiceName, fleetStatusDetails2.getComponentStatusDetails().get(0).getComponentName());
    }
}
