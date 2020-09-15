/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.PublishRequest;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.kernel.EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.getTELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.getTELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.getTELEMETRY_METRICS_PUBLISH_TOPICS;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class MetricsAgentTest extends EGServiceTestUtil {
    @TempDir
    protected Path tempRootDir;
    @Mock
    private MqttClient mockMqttClient;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Captor
    private ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor;
    @Captor
    private ArgumentCaptor<MqttClientConnectionEvents> mqttClientConnectionEventsArgumentCaptor;
    private MetricsAgent metricsAgent;
    @Mock
    private ScheduledExecutorService ses;
    @Mock
    private Kernel kernel;

    @BeforeEach
    public void setup() {
        serviceFullName = "MetricsAgentService";
        initializeMockedConfig();
        ses = new ScheduledThreadPoolExecutor(3);
        Topic periodicAggregateMetricsIntervalSec = Topic.of(context, getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC(), "1");
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC()))
                .thenReturn(periodicAggregateMetricsIntervalSec);
        Topic periodicPublishMetricsIntervalSec = Topic.of(context, getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC(), "3");
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC()))
                .thenReturn(periodicPublishMetricsIntervalSec);
        Topic lastPeriodicAggregateTime = Topic.of(context, getTELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC(),
                Instant.now().toEpochMilli());
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC()))
                .thenReturn(lastPeriodicAggregateTime);
        Topic lastPeriodicPublishTime = Topic.of(context, getTELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC(),
                Instant.now().toEpochMilli());
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC()))
                .thenReturn(lastPeriodicPublishTime);
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        when(config.lookup(DEVICE_PARAM_THING_NAME)).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        Topic telemetryMetricsPublishTopic = Topic.of(context, getTELEMETRY_METRICS_PUBLISH_TOPICS(),
                DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC);
        when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_METRICS_PUBLISH_TOPICS()))
                .thenReturn(telemetryMetricsPublishTopic);
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        metricsAgent = spy(new MetricsAgent(config, mockMqttClient, mockDeviceConfiguration, kernel, ses));
    }

    @AfterEach
    public void cleanUp() {
        ses.shutdownNow();
        metricsAgent.shutdown();
    }

    @Test
    public void GIVEN_Metrics_Agent_WHEN_starts_up_THEN_schedule_operations_on_metrics() throws InterruptedException {
        assertNull(metricsAgent.getPeriodicAggregateMetricsFuture());
        assertNull(metricsAgent.getPeriodicPublishMetricsFuture());

        metricsAgent.startup();
        assertNotNull(metricsAgent.getPeriodicAggregateMetricsFuture());
        assertNotNull(metricsAgent.getPeriodicPublishMetricsFuture());
    }

    @Test
    public void GIVEN_Metrics_Agent_WHEN_starts_up_THEN_periodically_schedule_operations() throws InterruptedException {
        doNothing().when(metricsAgent).aggregatePeriodicMetrics();
        doNothing().when(metricsAgent).publishPeriodicMetrics();
        metricsAgent.startup();
        long milliSeconds = 4000;
        Topic periodicAggregateMetricsIntervalSec = Topic.of(context, getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC(), "5");
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC()))
                .thenReturn(periodicAggregateMetricsIntervalSec);
        // aggregation starts at 5th second but we are checking only for 3 seconds
        verify(metricsAgent, timeout(milliSeconds).times(0)).aggregatePeriodicMetrics();
        // publish can start anytime between 0 to 3 seconds
        verify(metricsAgent, timeout(milliSeconds).atLeastOnce()).publishPeriodicMetrics();
        reset(metricsAgent);
        periodicAggregateMetricsIntervalSec = Topic.of(context, getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC(), "2");
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC()))
                .thenReturn(periodicAggregateMetricsIntervalSec);

        // aggregation starts at least at the 2nd sec
        verify(metricsAgent, timeout(milliSeconds).atLeastOnce()).aggregatePeriodicMetrics();
    }

    @Test
    public void GIVEN_Metrics_Agent_WHEN_mqtt_is_interrupted_THEN_aggregation_continues_but_publishing_stops()
            throws InterruptedException {
        Topic periodicPublishMetricsIntervalSec = Topic.of(context, getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC(), "2");
        lenient().when(config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, getTELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC()))
                .thenReturn(periodicPublishMetricsIntervalSec);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());
        metricsAgent.startup();
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);
        //verify that nothing is published when mqtt is interrupted
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
        long milliSeconds = 2000;
        // aggregation is continued irrespective of the mqtt connection
        verify(metricsAgent, timeout(milliSeconds).atLeast(1)).aggregatePeriodicMetrics();
        //verify that metrics are published atleast once in the periodic interval when the connection resumes
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(true);
        verify(mockMqttClient, timeout(milliSeconds).atLeast(1)).publish(publishRequestArgumentCaptor.capture());
        PublishRequest request = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, request.getQos());
        assertEquals("$aws/things/testThing/greengrassv2/health/json", request.getTopic());
    }
}

