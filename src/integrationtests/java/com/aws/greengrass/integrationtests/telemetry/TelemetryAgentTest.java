/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.telemetry;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.telemetry.MetricsPayload;
import com.aws.greengrass.telemetry.TelemetryAgent;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.atLeast;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class TelemetryAgentTest extends BaseITCase {
    private Kernel kernel;
    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;
    private TelemetryAgent ta;

    @BeforeEach
    void before() {
        kernel = new Kernel();
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_kernel_running_with_telemetry_config_WHEN_launch_THEN_metrics_are_published()
            throws InterruptedException {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("config.yaml").toString());
        kernel.getContext().put(MqttClient.class, mqttClient);
        //WHEN
        CountDownLatch telemetryRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals(TELEMETRY_AGENT_SERVICE_TOPICS) &&
                    service.getState().equals(State.RUNNING)) {
                telemetryRunning.countDown();
                ta = (TelemetryAgent) service;
            }

        });
        kernel.launch();
        assertTrue(telemetryRunning.await(10, TimeUnit.SECONDS), "TelemetryAgent is not in RUNNING state.");
        Topics telTopics = kernel.findServiceTopic(TELEMETRY_AGENT_SERVICE_TOPICS);
        assertNotNull(telTopics);
        int aggregateInterval = Coerce.toInt(telTopics.find(PARAMETERS_CONFIG_KEY,
                TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC));
        int periodicInterval = Coerce.toInt(telTopics.find(PARAMETERS_CONFIG_KEY,
                TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC));
        //telemetry configurations are set correctly
        assertEquals(2, aggregateInterval);
        assertEquals(4, periodicInterval);
        long lastAgg = Coerce.toLong(telTopics.find(RUNTIME_STORE_NAMESPACE_TOPIC,
                TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC));

        //wait till the first publish
        TimeUnit.SECONDS.sleep(periodicInterval + 1);
        assertTrue(Coerce.toLong(telTopics.find(RUNTIME_STORE_NAMESPACE_TOPIC,
                TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC)) > lastAgg);
        assertNotNull(ta.getPeriodicPublishMetricsFuture(), "periodic publish future is not scheduled.");
        long delay = ta.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        assertTrue(delay <= periodicInterval);
        // telemetry logs are always written to ~root/telemetry
        assertEquals(kernel.getNucleusPaths().rootPath().resolve("telemetry"),
                TelemetryConfig.getTelemetryDirectory());
        // THEN
        if(delay < aggregateInterval) {
            verify(mqttClient, atLeast(0)).publish(captor.capture());
        } else {
            verify(mqttClient, atLeastOnce()).publish(captor.capture());
            List<PublishRequest> prs = captor.getAllValues();
            for (PublishRequest pr : prs) {
                try {
                    MetricsPayload mp = new ObjectMapper().readValue(pr.getPayload(), MetricsPayload.class);
                    assertEquals(QualityOfService.AT_LEAST_ONCE, pr.getQos());
                    assertEquals(DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", ""), pr.getTopic());
                    assertEquals("2020-07-30", mp.getSchema());
                    // enough to verify the first message of type MetricsPayload
                    break;
                } catch (IOException e) {
                    fail("The message received at this topic is not of MetricsPayload type.", e);
                }
            }
        }

    }
}

