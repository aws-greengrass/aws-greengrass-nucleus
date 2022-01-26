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
import com.aws.greengrass.status.FleetStatusDetails;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class FleetStatusServiceSetupTest extends BaseITCase {
    private static Kernel kernel;
    private static DeviceConfiguration deviceConfiguration;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private AtomicReference<FleetStatusDetails> fleetStatusDetails;
    @Mock
    private MqttClient mqttClient;

    @BeforeEach
    void setupKernel() throws Exception {
        fleetStatusDetails = new AtomicReference<>();
        kernel = new Kernel();
        ConfigPlatformResolver.initKernelWithMultiPlatformConfig(kernel,
                FleetStatusServiceSetupTest.class.getResource("onlyMain.yaml"));
        kernel.getContext().put(MqttClient.class, mqttClient);

        when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
            Object argument = i.getArgument(0);
            PublishRequest publishRequest = (PublishRequest) argument;
            try {
                fleetStatusDetails.set(OBJECT_MAPPER.readValue(publishRequest.getPayload(), FleetStatusDetails.class));
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
    void GIVEN_kernel_deployment_WHEN_device_provisioning_completes_before_kernel_launches_THEN_thing_details_uploaded_to_cloud()
            throws Exception {

        deviceConfiguration = new DeviceConfiguration(kernel, "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath", "certFilePath", "caFilePath",
                "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        kernel.launch();

        assertThat(kernel.locate(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)::getState, eventuallyEval(is(State.RUNNING)));
        assertEquals("ThingName", Coerce.toString(deviceConfiguration.getThingName()));
        assertThat(()-> fleetStatusDetails.get(), eventuallyEval(notNullValue(), Duration.ofSeconds(30)));
        assertThat(() -> fleetStatusDetails.get().getThing(), eventuallyEval(is("ThingName"), Duration.ofSeconds(30)));
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
    }

    @Test
    void GIVEN_kernel_deployment_WHEN_device_configs_change_THEN_FSSsetup_is_not_rerun()
            throws Exception {
        deviceConfiguration = new DeviceConfiguration(kernel, "ThingName", "xxxxxx-ats.iot.us-east-1.amazonaws.com",
                "xxxxxx.credentials.iot.us-east-1.amazonaws.com", "privKeyFilePath", "certFilePath", "caFilePath",
                "us-east-1", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        kernel.launch();
        assertThat(kernel.locate(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)::getState, eventuallyEval(is(State.RUNNING)));

        deviceConfiguration.getIotDataEndpoint().withValue("new-ats.iot.us-east-1.amazonaws.com");
        assertEquals("new-ats.iot.us-east-1.amazonaws.com", Coerce.toString(deviceConfiguration.getIotDataEndpoint()));

        // Verify publishing happens once each for IoTJobs, ShadowDeploymentService, and FSS
        verify(mqttClient, times(3)).publish(any(PublishRequest.class));
    }
}