/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.status;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.status.FleetStatusDetails;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.status.OverallStatus;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class PeriodicFleetStatusServiceTest extends BaseITCase {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static DeviceConfiguration deviceConfiguration;
    private static Kernel kernel;
    private Consumer<GreengrassLogMessage> logListener;
    private final Set<String> componentNamesToCheck = new HashSet<>();

    @TempDir
    static Path rootDir;
    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;

    @BeforeEach
    void setupKernel() throws DeviceConfigurationException, InterruptedException {
        System.setProperty("root", rootDir.toAbsolutePath().toString());
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
        deviceConfiguration = new DeviceConfiguration(kernel, "ThingName", "dataEndpoint", "credEndpoint",
                "privKeyFilePath", "certFilePath", "caFilePath", "awsRegion");
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
        CountDownLatch fssPublishLatch = new CountDownLatch(1);
        logListener = eslm -> {
            if (eslm.getEventType() != null && eslm.getEventType().equals("fss-status-update-published")
                    && eslm.getMessage().equals("Status update published to FSS")) {
                fssPublishLatch.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);

        assertTrue(fssPublishLatch.await(30, TimeUnit.SECONDS));
        verify(mqttClient, atLeastOnce()).publish(captor.capture());

        List<PublishRequest> prs = captor.getAllValues();
        for (PublishRequest pr : prs) {
            try {
                FleetStatusDetails fleetStatusDetails = OBJECT_MAPPER.readValue(pr.getPayload(),
                        FleetStatusDetails.class);
                assertEquals("ThingName", fleetStatusDetails.getThing());
                assertEquals(OverallStatus.HEALTHY, fleetStatusDetails.getOverallStatus());
                assertEquals(0, fleetStatusDetails.getSequenceNumber());
                assertNotNull(fleetStatusDetails.getComponentStatusDetails());
                assertEquals(componentNamesToCheck.size(), fleetStatusDetails.getComponentStatusDetails().size());
                fleetStatusDetails.getComponentStatusDetails().forEach(componentStatusDetails -> {
                    componentNamesToCheck.remove(componentStatusDetails.getComponentName());
                });
            } catch (UnrecognizedPropertyException ignored) { }
        }
        assertEquals(0, componentNamesToCheck.size());
    }
}
