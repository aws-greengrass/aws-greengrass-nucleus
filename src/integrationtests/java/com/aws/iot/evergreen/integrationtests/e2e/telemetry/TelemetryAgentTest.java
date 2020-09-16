/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.telemetry;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.SubscribeRequest;
import com.aws.iot.evergreen.telemetry.MetricsPayload;
import com.aws.iot.evergreen.telemetry.TelemetryAgent;
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
import static com.aws.iot.evergreen.telemetry.TelemetryAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.iot.evergreen.telemetry.TelemetryAgent.TELEMETRY_METRICS_PUBLISH_TOPICS;
import static com.aws.iot.evergreen.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.iot.evergreen.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("PMD.CloseResource")
@ExtendWith(EGExtension.class)
@Tag("E2E")
public class TelemetryAgentTest extends BaseE2ETestCase {
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
    void GIVEN_kernel_running_WHEN_telemetry_agent_starts_THEN_metrics_are_published_to_Cloud() throws
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
        Topics telemetryRuntimeTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC,
                TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS, RUNTIME_STORE_NAMESPACE_TOPIC);
        telemetryRuntimeTopics.lookup(TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .withValue(aggInterval);
        telemetryRuntimeTopics.lookup(TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC)
                .withValue(pubInterval);
        // TODO Remove this when $aws topic is allowed in the end to end tests
        String workingE2ETopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("$aws", "aws");
        telemetryRuntimeTopics.lookup(TELEMETRY_METRICS_PUBLISH_TOPICS).withValue(workingE2ETopic);
        String telemetryTopic = workingE2ETopic.replace("{thingName}", thingInfo.getThingName());
        client.subscribe(SubscribeRequest.builder()
                .topic(telemetryTopic)
                .callback((m) -> {
                    cdl.countDown();
                    mqttMessagesList.get().add(m);
                })
                .build());
        assertTrue(cdl.await(30, TimeUnit.SECONDS), "All messages published and received");

        // We expect that the Metrics Agent service to be available
        TelemetryAgent ma = (TelemetryAgent) kernel.locate("TelemetryAgent");
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
        assertNotNull(mp, " Failed to publish telemetry data in the given interval");
        assertEquals("2020-07-30", mp.getSchema());
    }
}
