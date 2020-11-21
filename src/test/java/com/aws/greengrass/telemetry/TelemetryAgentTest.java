/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelMetricsEmitter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
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

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class TelemetryAgentTest extends GGServiceTestUtil {
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
    private ScheduledExecutorService ses;
    private TelemetryAgent telemetryAgent;
    private SystemMetricsEmitter sme;
    private KernelMetricsEmitter kme;
    private MetricsAggregator ma;

    @BeforeEach
    void setup() {
        serviceFullName = "MetricsAgent";
        initializeMockedConfig();
        TelemetryConfig.getInstance().setRoot(tempRootDir);
        kme = new KernelMetricsEmitter(mock(Kernel.class));
        sme = new SystemMetricsEmitter();
        ma = new MetricsAggregator();
        ses = new ScheduledThreadPoolExecutor(3);
        Topic periodicAggregateMetricsIntervalSec = Topic.of(context, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC, "100");
        lenient().when(config.lookup(CONFIGURATION_CONFIG_KEY, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC))
                .thenReturn(periodicAggregateMetricsIntervalSec);
        Topic periodicPublishMetricsIntervalSec = Topic.of(context, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC, "300");
        lenient().when(config.lookup(CONFIGURATION_CONFIG_KEY, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC))
                .thenReturn(periodicPublishMetricsIntervalSec);
        Topic lastPeriodicAggregateTime = Topic.of(context, TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC,
                Instant.now().toEpochMilli());
        lenient().when(config.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC)
                .lookup(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC))
                .thenReturn(lastPeriodicAggregateTime);
        Topic lastPeriodicPublishTime = Topic.of(context, TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC,
                Instant.now().toEpochMilli());
        lenient().when(config.lookupTopics(RUNTIME_STORE_NAMESPACE_TOPIC)
                .lookup(TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC))
                .thenReturn(lastPeriodicPublishTime);
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        lenient().when(config.lookup(DEVICE_PARAM_THING_NAME))
                .thenReturn(thingNameTopic);
        telemetryAgent = spy(new TelemetryAgent(config, mockMqttClient, mockDeviceConfiguration, ma, sme, kme, ses,
                3, 1));
    }

    @AfterEach
    void cleanUp() throws IOException, InterruptedException {
        TelemetryConfig.getInstance().closeContext();
        telemetryAgent.shutdown();
        ses.shutdown();
        context.close();
        ses.awaitTermination(2, TimeUnit.SECONDS);
    }

    @Test
    void GIVEN_Telemetry_Agent_WHEN_starts_up_THEN_schedule_operations_on_metrics() throws InterruptedException {
        assertNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNull(p.future);
        }

        telemetryAgent.startup();
        assertNotNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNotNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNotNull(p.future);
        }
    }

    @Test
    void GIVEN_periodic_update_less_than_default_WHEN_config_read_THEN_sets_publish_interval_to_default() throws InterruptedException {
        telemetryAgent = spy(new TelemetryAgent(config, mockMqttClient, mockDeviceConfiguration, ma, sme, kme, ses));
        assertNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNull(p.future);
        }

        telemetryAgent.startup();
        assertNotNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNotNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNotNull(p.future);
        }

        assertEquals(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC, telemetryAgent.getPeriodicAggregateMetricsIntervalSec());
        assertEquals(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC, telemetryAgent.getPeriodicPublishMetricsIntervalSec());
    }

    @Test
    void GIVEN_Telemetry_Agent_WHEN_starts_up_THEN_periodically_schedule_operations() throws InterruptedException {
        doNothing().when(telemetryAgent).aggregatePeriodicMetrics();
        doNothing().when(telemetryAgent).publishPeriodicMetrics();

        telemetryAgent.startup();
        long milliSeconds = 4000;
        telemetryAgent.setPeriodicAggregateMetricsIntervalSec(5);
        telemetryAgent.schedulePeriodicAggregateMetrics(false);
        // aggregation starts at 5th second but we are checking only for 3 seconds
        verify(telemetryAgent, timeout(milliSeconds).times(0)).aggregatePeriodicMetrics();
        // publish can start anytime between 0 to 3 seconds
        verify(telemetryAgent, timeout(milliSeconds).atLeastOnce()).publishPeriodicMetrics();
        reset(telemetryAgent);
        telemetryAgent.setPeriodicAggregateMetricsIntervalSec(2);
        telemetryAgent.schedulePeriodicAggregateMetrics(false);
        // aggregation starts at least at the 2nd sec
        verify(telemetryAgent, timeout(milliSeconds).atLeastOnce()).aggregatePeriodicMetrics();
    }

    @Test
    void GIVEN_Telemetry_Agent_WHEN_mqtt_is_interrupted_THEN_aggregation_continues_but_publishing_stops()
            throws InterruptedException {
        telemetryAgent.setPeriodicPublishMetricsIntervalSec(2);
        telemetryAgent.schedulePeriodicPublishMetrics(false);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());
        telemetryAgent.startup();
        long milliSeconds = 3000;
        verify(mockMqttClient, timeout(milliSeconds).atLeastOnce()).publish(publishRequestArgumentCaptor.capture());
        PublishRequest request = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, request.getQos());
        assertEquals("$aws/things/testThing/greengrass/health/json", request.getTopic());
        reset(telemetryAgent, mockMqttClient);
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);
        //verify that nothing is published when mqtt is interrupted
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
        // aggregation is continued irrespective of the mqtt connection
        verify(telemetryAgent, timeout(milliSeconds).atLeastOnce()).aggregatePeriodicMetrics();
    }
}

