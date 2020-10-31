/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.status;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.ComponentStatusDetails;
import com.aws.greengrass.status.FleetStatusDetails;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.status.OverallStatus;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.exceptions.TLSAuthException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class PeriodicFleetStatusServiceTest extends BaseITCase {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static DeviceConfiguration deviceConfiguration;
    private static Kernel kernel;
    private CountDownLatch allComponentsInFssUpdate;
    private AtomicReference<FleetStatusDetails> fleetStatusDetails;

    @Mock
    private MqttClient mqttClient;

    @BeforeEach
    void setupKernel(ExtensionContext context) throws DeviceConfigurationException, InterruptedException {
        ignoreExceptionOfType(context, TLSAuthException.class);
        CountDownLatch fssRunning = new CountDownLatch(1);
        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        allComponentsInFssUpdate = new CountDownLatch(1);
        fleetStatusDetails = new AtomicReference<>();
        CompletableFuture cf = new CompletableFuture();
        cf.complete(null);
        kernel = new Kernel();
        kernel.parseArgs("-i", IotJobsFleetStatusServiceTest.class.getResource("smallPeriodicIntervalConfig.yaml").toString());
        kernel.getContext().put(MqttClient.class, mqttClient);

        when(mqttClient.publish(any(PublishRequest.class))).thenAnswer(i -> {
            Object argument = i.getArgument(0);
            PublishRequest publishRequest = (PublishRequest) argument;
            try {
                fleetStatusDetails.set(OBJECT_MAPPER.readValue(publishRequest.getPayload(),
                        FleetStatusDetails.class));
                if (kernel.orderedDependencies().size() == fleetStatusDetails.get().getComponentStatusDetails().size()) {
                    allComponentsInFssUpdate.countDown();
                }
            } catch (JsonMappingException ignored) { }
            return CompletableFuture.completedFuture(0);
        });

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)
                    && newState.equals(State.RUNNING)) {
                fssRunning.countDown();
            }
            if (service.getName().equals(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)
                    && newState.equals(State.RUNNING)) {
                deploymentServiceRunning.countDown();
            }
        });
        // set required instances from context
        deviceConfiguration =
                new DeviceConfiguration(kernel, "ThingName", "dataEndpoint", "credEndpoint", "privKeyFilePath",
                        "certFilePath", "caFilePath", "awsRegion", "roleAliasName");
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);
        kernel.launch();
        assertTrue(deploymentServiceRunning.await(10, TimeUnit.SECONDS));
        assertTrue(fssRunning.await(10, TimeUnit.SECONDS));
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_config_with_small_periodic_interval_WHEN_interval_elapses_THEN_status_is_uploaded_to_cloud()
            throws Exception {
        ((Map) kernel.getContext().getvIfExists(Kernel.SERVICE_TYPE_TO_CLASS_MAP_KEY).get()).put("plugin",
                GreengrassService.class.getName());
        assertNotNull(deviceConfiguration.getThingName());
        // Wait for some time for the publish request to have all the components update.
        assertTrue(allComponentsInFssUpdate.await(30, TimeUnit.SECONDS), "component publish requests");
        assertNotNull(fleetStatusDetails);
        assertNotNull(fleetStatusDetails.get());
        assertEquals("ThingName", fleetStatusDetails.get().getThing());
        assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.get().getOverallStatus());
        assertNotNull(fleetStatusDetails.get().getComponentStatusDetails());
        for (ComponentStatusDetails componentStatusDetail : fleetStatusDetails.get().getComponentStatusDetails()) {
            assertNotNull(componentStatusDetail.getComponentName());
            assertNotNull(componentStatusDetail.getFleetConfigArns());
            assertNotNull(componentStatusDetail.getState());
        }
    }
}
