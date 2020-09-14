/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.telemetry;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.SubscribeRequest;
import com.aws.iot.evergreen.telemetry.MetricsAgent;
import com.aws.iot.evergreen.telemetry.MetricsPayload;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.crt.mqtt.MqttMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.iot.evergreen.kernel.EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith(EGExtension.class)
@Tag("E2E")
public class MetricsAgentTest extends BaseE2ETestCase {
    private static final ObjectMapper DESERIALIZER = new ObjectMapper();

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
    }

    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_finishes_THEN_fss_data_is_uploaded() throws
            InterruptedException, ExecutionException, TimeoutException, ServiceLoadException {
        /*
         Metrics agent is an auto-start service. It publishes data to the cloud irrespective of the deployments.
         In this test, we just start the kernel and expect MA to publish time-based metrics such as system metrics and
         kernel component state metrics in the given interval.
        */
        MqttClient client = kernel.getContext().get(MqttClient.class);
        CountDownLatch cdl = new CountDownLatch(1);
        AtomicReference<List<MqttMessage>> mqttMessagesList = new AtomicReference<>();
        mqttMessagesList.set(new ArrayList<>());
        long aggInterval = 1;
        long pubInterval = 5;
        MetricsPayload mp = null;
        Topics maTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                MetricsAgent.METRICS_AGENT_SERVICE_TOPICS);
        maTopics.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, MetricsAgent.getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC())
                .withValue(aggInterval);
        maTopics.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, MetricsAgent.getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC())
                .withValue(pubInterval);
        String telemetryTopic = MetricsAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC
                .replace("{thingName}", thingInfo.getThingName());
        client.subscribe(SubscribeRequest.builder()
                .topic(telemetryTopic)
                .callback((m) -> {
                    cdl.countDown();
                    mqttMessagesList.get().add(m);
                })
                .build());
        cdl.await(30, TimeUnit.SECONDS);

        // We expect that the Metrics Agent service to be available
        MetricsAgent ma = (MetricsAgent) kernel.locate("MetricsAgent");
        assertEquals(ma.getState(), State.RUNNING);

        long delay = ma.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        // the delay for publishing the interval must be less than the publish interval set.
        assertTrue(delay < pubInterval);

        for (MqttMessage mt : mqttMessagesList.get()) {
            // In this test, we filter only the metrics payload.
            if (mt.getTopic().equals(telemetryTopic)) {
                try {
                    mp = DESERIALIZER.readValue(mt.getPayload(), MetricsPayload.class);
                } catch (IOException e) {
                    //do nothing if it not a metrics payload message.
                }
            }
        }
        if (mp != null) {
            assertEquals("2020-07-30", mp.getSchema());
            /*
             In this test, aggregated metrics logs are emitted once every second.
             So,for eg, if publishing the metrics starts randomly at the 3rd second, metrics that were aggregated in
             1,2,3 seconds are published which contains a list of n entries each where n is the no of namespaces(3rd
             second aggregations are not included if publishing thread starts before aggregation and hence the >=).
            */
            assertTrue(delay * TelemetryNamespace.values().length >= mp.getAggregatedMetricList().size());
        } else {
            fail("Telemetry data is not published in the given interval");
        }
    }
}
