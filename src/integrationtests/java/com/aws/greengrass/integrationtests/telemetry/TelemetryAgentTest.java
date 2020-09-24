/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.greengrass.integrationtests.telemetry;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.telemetry.MetricsAggregator;
import com.aws.greengrass.telemetry.MetricsPayload;
import com.aws.greengrass.telemetry.TelemetryAgent;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class TelemetryAgentTest extends BaseITCase {
    private Kernel kernel;
    @Mock
    private MqttClient mqttClient;
    @Captor
    private ArgumentCaptor<PublishRequest> captor;

    @BeforeEach
    void before(TestInfo testInfo) {
        kernel = new Kernel();
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_kernel_running_with_telemetry_config_WHEN_launch_THEN_metrics_are_published() throws InterruptedException,
            ServiceLoadException {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("config.yaml").toString());
        kernel.getContext().put(MqttClient.class, mqttClient);
        //WHEN
        kernel.launch();
        CountDownLatch telemetryRunning = new CountDownLatch(1);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("TelemetryAgent") && service.getState().equals(State.RUNNING)) {
                telemetryRunning.countDown();
            }
        });
        assertTrue(telemetryRunning.await(1, TimeUnit.MINUTES), "TelemetryAgent is not in RUNNING state.");
        Topics parameterTopics = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, TELEMETRY_AGENT_SERVICE_TOPICS, PARAMETERS_CONFIG_KEY);
        int aggregateInterval = Coerce.toInt(parameterTopics.find(TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC));
        int periodicInterval = Coerce.toInt(parameterTopics.find(TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC));
        TelemetryAgent ma = (TelemetryAgent) kernel.locate("TelemetryAgent");
        long delay = ma.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        assertTrue(delay <= periodicInterval);
        //telemetry configurations are set correctly
        assertEquals(2, aggregateInterval);
        assertEquals(4, periodicInterval);
        Topics runtimeTopics = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, TELEMETRY_AGENT_SERVICE_TOPICS, RUNTIME_STORE_NAMESPACE_TOPIC);
        long lastAgg = Coerce.toLong(runtimeTopics.find(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC));

        //wait till the first publish
        TimeUnit.SECONDS.sleep(periodicInterval + 1);
        assertTrue(Coerce.toLong(runtimeTopics.find(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC)) > lastAgg);


        // telemetry logs are always written to ~root/telemetry
        assertEquals(kernel.getRootPath().resolve("telemetry"), TelemetryConfig.getTelemetryDirectory());
        // THEN
        verify(mqttClient, atLeastOnce()).publish(captor.capture());
        List<PublishRequest> prs = captor.getAllValues();
        for (PublishRequest pr : prs) {
            try {
                MetricsPayload mp = new ObjectMapper().readValue(pr.getPayload(), MetricsPayload.class);
                int count = (int) (delay / aggregateInterval + 1) * MetricsAggregator.getNamespaceSet().size();
                // > is in the cases where delay < aggregate interval
                assertTrue(count >= mp.getAggregatedMetricList().size());
                assertEquals(QualityOfService.AT_LEAST_ONCE, pr.getQos());
                assertEquals(DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", ""), pr.getTopic());
                assertEquals("2020-07-30", mp.getSchema());
                // enough to verify the first message of type MetricsPayload
                break;
            } catch (IOException e) {
                fail("The meessage received at this topic is not of MetricsPaylod type.");
            }
        }
    }
}

