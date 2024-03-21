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
import com.aws.greengrass.status.model.MessageType;
import com.aws.greengrass.status.model.Trigger;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.status.FleetStatusService.FLEET_STATUS_MESSAGE_PUBLISH_MIN_WAIT_TIME_SEC;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class FleetStatusServiceSetupTest extends BaseITCase {
    private static Kernel kernel;
    private static DeviceConfiguration deviceConfiguration;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private AtomicReference<FleetStatusDetails> fleetStatusDetails;
    private AtomicReference<List<FleetStatusDetails>> fleetStatusDetailsList;
    private final CountDownLatch statusChange = new CountDownLatch(1);
    @Mock
    private MqttClient mqttClient;

    @BeforeEach
    void setupKernel() throws Exception {
        fleetStatusDetails = new AtomicReference<>();
        fleetStatusDetailsList = new AtomicReference<>(new ArrayList<>());
        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                FleetStatusServiceSetupTest.class.getResource("onlyMain.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);

        when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
            Object argument = i.getArgument(0);
            PublishRequest publishRequest = (PublishRequest) argument;
            try {
                fleetStatusDetails.set(OBJECT_MAPPER.readValue(publishRequest.getPayload(), FleetStatusDetails.class));
                fleetStatusDetailsList.get().add(fleetStatusDetails.get());
                if (fleetStatusDetails.get().getTrigger().equals(Trigger.COMPONENT_STATUS_CHANGE)) {
                    statusChange.countDown();
                }
            } catch (JsonMappingException ignored) {
            }
            return CompletableFuture.completedFuture(0);
        });
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_kernel_launches_THEN_thing_details_and_components_terminal_states_uploaded_to_cloud_3s_after_launch_message()
            throws Exception {
        deviceConfiguration = new DeviceConfiguration(kernel, "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath", "certFilePath", "caFilePath",
                "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        kernel.launch();

        assertThat(kernel.locate(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)::getState, eventuallyEval(is(State.RUNNING)));
        assertEquals("ThingName", Coerce.toString(deviceConfiguration.getThingName()));

        // we should send two status updates within 4 seconds; 1 nucleus launch  and 1 component status change
        assertTrue(statusChange.await(FLEET_STATUS_MESSAGE_PUBLISH_MIN_WAIT_TIME_SEC + 1L, TimeUnit.SECONDS));
        fleetStatusDetailsList.get().removeIf(f -> Trigger.NETWORK_RECONFIGURE.equals(f.getTrigger()));
        assertEquals(2, fleetStatusDetailsList.get().size());
        // first message is nucleus launch
        FleetStatusDetails fssDetails = fleetStatusDetailsList.get().get(0);
        assertEquals("ThingName", fssDetails.getThing());
        assertEquals(MessageType.COMPLETE, fssDetails.getMessageType());
        assertEquals(Trigger.NUCLEUS_LAUNCH, fssDetails.getTrigger());
        Instant launchTimestamp = Instant.ofEpochMilli(fssDetails.getTimestamp());
        // second message is component status change
        fssDetails = fleetStatusDetailsList.get().get(1);
        assertEquals(MessageType.PARTIAL, fssDetails.getMessageType());
        assertEquals(Trigger.COMPONENT_STATUS_CHANGE, fssDetails.getTrigger());
        Instant terminalTimestamp = Instant.ofEpochMilli(fssDetails.getTimestamp());
        assertEquals(FLEET_STATUS_MESSAGE_PUBLISH_MIN_WAIT_TIME_SEC, Duration.between(launchTimestamp, terminalTimestamp).getSeconds());

    }

    @Test
    void GIVEN_kernel_deployment_WHEN_device_provisioning_completes_after_kernel_has_launched_THEN_thing_details_uploaded_to_cloud()
            throws Exception {
        kernel.launch();
        assertThat(kernel.locate(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)::getState, eventuallyEval(is(State.RUNNING)));

        deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        deviceConfiguration.getThingName().withValue("ThingName");
        deviceConfiguration.getIotDataEndpoint().withValue("xxxxxx-ats.iot.us-east-1.amazonaws.com");
        deviceConfiguration.getIotCredentialEndpoint().withValue("xxxxxx.credentials.iot.us-east-1.amazonaws.com");
        deviceConfiguration.getPrivateKeyFilePath().withValue("privKeyFilePath");
        deviceConfiguration.getCertificateFilePath().withValue("certFilePath");
        deviceConfiguration.getRootCAFilePath().withValue("caFilePath");
        deviceConfiguration.getAWSRegion().withValue("us-east-1");
        deviceConfiguration.getIotRoleAlias().withValue("roleAliasName");

        assertEquals("ThingName", Coerce.toString(deviceConfiguration.getThingName()));
        assertThat(() -> fleetStatusDetails.get().getThing(), eventuallyEval(is("ThingName"), Duration.ofSeconds(30)));
        deviceConfiguration.getIotDataEndpoint().withValue("new-ats.iot.us-east-1.amazonaws.com");
        assertEquals("new-ats.iot.us-east-1.amazonaws.com", Coerce.toString(deviceConfiguration.getIotDataEndpoint()));

        // Verify have 1 publish request for each of IoTJobs, ShadowDeploymentService, and FSS
        ArgumentCaptor<PublishRequest> publishRequestCaptor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(mqttClient, timeout(5000).times(3)).publish(publishRequestCaptor.capture());
        List<PublishRequest> publishRequests = publishRequestCaptor.getAllValues();

        String IoTJobsTopic = "$aws/things/ThingName/shadow/name/AWSManagedGreengrassV2Deployment/get";
        String ShadowDeploymentServiceTopic = "$aws/things/ThingName/jobs/$next/namespace-aws-gg-deployment/get";
        String FSSTopic = "$aws/things/ThingName/greengrassv2/health/json";

        assertTrue(publishRequests.stream().anyMatch(pr -> pr.getTopic().equals(IoTJobsTopic)));
        assertTrue(publishRequests.stream().anyMatch(pr -> pr.getTopic().equals(ShadowDeploymentServiceTopic)));
        assertTrue(publishRequests.stream().anyMatch(pr -> pr.getTopic().equals(FSSTopic)));
    }
}
