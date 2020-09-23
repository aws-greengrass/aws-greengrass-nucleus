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
import static org.mockito.Mockito.timeout;
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
        System.out.println("Running test: " + testInfo.getDisplayName());
        kernel = new Kernel();
    }

    @AfterEach
    void after() {
        if (kernel != null) {
            mqttClient.close();
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_kernel_running_with_telemetry_config_WHEN_launch_THEN_metrics_are_published() throws InterruptedException,
            ServiceLoadException {
        // GIVEN
        kernel.parseArgs("-i", getClass().getResource("config.yaml").toString());
        CountDownLatch mainRunning = new CountDownLatch(2);
        kernel.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            if (service.getName().equals("main") && newState.equals(State.RUNNING)) {
                mainRunning.countDown();
            }
        });
        kernel.getContext().put(MqttClient.class, mqttClient);
        //WHEN
        kernel.launch();

        Topics parameterTopics = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, TELEMETRY_AGENT_SERVICE_TOPICS, PARAMETERS_CONFIG_KEY);
        int aggregateInterval = Coerce.toInt(parameterTopics.find(TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC));
        int periodicInterval = Coerce.toInt(parameterTopics.find(TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC));
        //telemetry configurations are set correctly
        assertEquals(3, aggregateInterval);
        assertEquals(2, periodicInterval);
        Topics runtimeTopics = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, TELEMETRY_AGENT_SERVICE_TOPICS, RUNTIME_STORE_NAMESPACE_TOPIC);
        long lastAgg = Coerce.toLong(runtimeTopics.find(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC));

        //wait for at least 4 seconds as the first aggregation occurs at 3rd second.
        Thread.sleep(4000);
        assertTrue(Coerce.toLong(runtimeTopics.find(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC)) > lastAgg);

        TelemetryAgent ma = (TelemetryAgent) kernel.locate("TelemetryAgent");
        long delay = ma.getPeriodicPublishMetricsFuture().getDelay(TimeUnit.SECONDS);
        assertTrue(delay < periodicInterval);
        // telemetry logs are always written to ~root/telemetry
        assertEquals(kernel.getRootPath().resolve("telemetry"), TelemetryConfig.getTelemetryDirectory());
        // THEN
        verify(mqttClient, timeout(1000).atLeastOnce()).publish(captor.capture());
        List<PublishRequest> prs = captor.getAllValues();
        for (PublishRequest pr : prs) {
            try {
                MetricsPayload mp = new ObjectMapper().readValue(pr.getPayload(), MetricsPayload.class);
                //There will be nothing to aggregate as publish happens at 1st second and aggregation at 2nd second.
                // So, there will only one accumulated data point for each namespace during the first publish
                assertEquals(kernel.getContext().get(MetricsAggregator.class)
                        .getNamespaceSet().getNamespaces().size(), mp.getAggregatedMetricList().size());
                assertEquals(QualityOfService.AT_LEAST_ONCE, pr.getQos());
                assertEquals(DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", ""), pr.getTopic());
                assertEquals("2020-07-30", mp.getSchema());
                // enough to verify the first message of type MetricsPayload
                break;
            } catch (IOException e) {
                System.out.println("Ignore if the publish message is not of MetricsPayload type");
            }
        }
    }
}

