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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class PeriodicFleetStatusServiceTest extends BaseITCase {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static DeviceConfiguration deviceConfiguration;
    private static Kernel kernel;
    private final Set<String> componentNamesToCheck = new HashSet<>();

    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;

    @BeforeEach
    void setupKernel(ExtensionContext context) throws DeviceConfigurationException, InterruptedException {
        ignoreExceptionOfType(context, TLSAuthException.class);
        CountDownLatch fssRunning = new CountDownLatch(1);
        CountDownLatch deploymentServiceRunning = new CountDownLatch(1);
        CompletableFuture cf = new CompletableFuture();
        cf.complete(null);
        kernel = new Kernel();
        kernel.parseArgs("-i", IotJobsFleetStatusServiceTest.class.getResource("smallPeriodicIntervalConfig.yaml").toString());
        kernel.getContext().put(MqttClient.class, mqttClient);

        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(FleetStatusService.FLEET_STATUS_SERVICE_TOPICS)
                    && newState.equals(State.RUNNING)) {
                fssRunning.countDown();
            }
            if (service.getName().equals(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)
                    && newState.equals(State.RUNNING)) {
                deploymentServiceRunning.countDown();
            }
            componentNamesToCheck.add(service.getName());
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
        CountDownLatch allComponentsInFssUpdate = new CountDownLatch(1);

        when(mqttClient.publish(captor.capture())).thenAnswer(i -> {
            Object argument = i.getArgument(0);
            PublishRequest publishRequest = (PublishRequest) argument;
            FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(publishRequest.getPayload(),
                    FleetStatusDetails.class);
            if (componentNamesToCheck.size() == fleetStatusDetails.getComponentStatusDetails().size()) {
                allComponentsInFssUpdate.countDown();
            }
            return CompletableFuture.completedFuture(0);
        });

        // Wait for some time for the publish request to have all the components update.
        assertTrue(allComponentsInFssUpdate.await(30, TimeUnit.SECONDS), "component publish requests");

        List<PublishRequest> prs = captor.getAllValues();
        // Get the last FSS publish request which should have all the components information.
        PublishRequest pr = prs.get(prs.size() - 1);
        try {
            FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(pr.getPayload(),
                    FleetStatusDetails.class);
            assertEquals("ThingName", fleetStatusDetails.getThing());
            assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
            assertNotNull(fleetStatusDetails.getComponentStatusDetails());
            String allUpdatedComponentNames = fleetStatusDetails.getComponentStatusDetails().stream()
                    .map(ComponentStatusDetails::getComponentName).collect(Collectors.joining(", "));
            assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size(),
                    "Not all components were updated. Updated Components names are: "
                            + allUpdatedComponentNames + ". All Components: " +
                            String.join(", ", componentNamesToCheck));
            fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                componentNamesToCheck.remove(componentStatusDetails.getComponentName());
            });
        } catch (UnrecognizedPropertyException ignored) {
        }
        assertEquals(0, componentNamesToCheck.size());
    }
}
