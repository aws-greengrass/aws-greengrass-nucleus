/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.KernelMetricsEmitter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.PublishRequest;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import com.aws.greengrass.testing.TestFeatureParameterInterface;
import com.aws.greengrass.testing.TestFeatureParameters;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.TELEMETRY_CONFIG_LOGGING_TOPICS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.greengrass.telemetry.TelemetryAgent.TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
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
    @Mock
    private TestFeatureParameterInterface DEFAULT_HANDLER;
    @Captor
    private ArgumentCaptor<PublishRequest> publishRequestArgumentCaptor;
    @Captor
    private ArgumentCaptor<Long> publishTimeArgumentCaptor;
    @Captor
    private ArgumentCaptor<MqttClientConnectionEvents> mqttClientConnectionEventsArgumentCaptor;
    private ScheduledExecutorService ses;
    private ExecutorService executorService;
    private TelemetryAgent telemetryAgent;
    @Mock
    private SystemMetricsEmitter sme;
    @Mock
    private KernelMetricsEmitter kme;
    @Mock
    private MetricsAggregator ma;

    @BeforeEach
    void setup() {
        serviceFullName = "MetricsAgent";
        initializeMockedConfig();
        TelemetryConfig.getInstance().setRoot(tempRootDir);
        ses = new ScheduledThreadPoolExecutor(3);
        executorService = Executors.newCachedThreadPool();
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
        when(mockDeviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);

        lenient().when(config.lookup(DEVICE_PARAM_THING_NAME)).thenReturn(thingNameTopic);
        Topics configurationTopics = Topics.of(context, TELEMETRY_CONFIG_LOGGING_TOPICS, null);
        configurationTopics.createLeafChild("enabled").withValue(true);
        configurationTopics.createLeafChild("periodicAggregateMetricsIntervalSeconds").withValue(100);
        configurationTopics.createLeafChild("periodicPublishMetricsIntervalSeconds").withValue(300);
        lenient().when(mockDeviceConfiguration.getTelemetryConfigurationTopics()).thenReturn(configurationTopics);
        lenient().doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());
        telemetryAgent = new TelemetryAgent(config, mockMqttClient, mockDeviceConfiguration, ma, sme, kme, ses, executorService,
                3, 1);
    }

    @AfterEach
    void cleanUp() throws IOException, InterruptedException {
        TelemetryConfig.getInstance().closeContext();
        telemetryAgent.shutdown();
        ses.shutdownNow();
        executorService.shutdownNow();
        context.close();
        ses.awaitTermination(5, TimeUnit.SECONDS);
        executorService.awaitTermination(5, TimeUnit.SECONDS);
        TestFeatureParameters.unRegisterHandlerCallback(serviceFullName);
        TestFeatureParameters.internalDisableTestingFeatureParameters();
    }

    @Test
    void GIVEN_Telemetry_Agent_WHEN_starts_up_THEN_schedule_operations_on_metrics() throws InterruptedException {
        telemetryAgent.postInject();
        TimeUnit.SECONDS.sleep(1);

        assertNotNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNotNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNotNull(p.future);
        }
    }

    @Test
    void GIVEN_periodic_update_less_than_default_WHEN_config_read_THEN_sets_publish_interval_to_default() throws InterruptedException {
        telemetryAgent = spy(new TelemetryAgent(config, mockMqttClient, mockDeviceConfiguration, ma, sme, kme, ses, executorService));
        telemetryAgent.postInject();
        TimeUnit.SECONDS.sleep(1);
        assertNotNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNotNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNotNull(p.future);
        }

        assertNotNull(telemetryAgent.getCurrentConfiguration());
        assertEquals(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC, telemetryAgent.getCurrentConfiguration().get().getPeriodicAggregateMetricsIntervalSeconds());
        assertEquals(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC, telemetryAgent.getCurrentConfiguration().get().getPeriodicPublishMetricsIntervalSeconds());
    }

    @Test
    void GIVEN_telemetry_not_enabled_WHEN_config_read_THEN_does_not_publish() throws InterruptedException {
        Topics configurationTopics = Topics.of(context, TELEMETRY_CONFIG_LOGGING_TOPICS, null);
        configurationTopics.createLeafChild("enabled").withValue(false);
        configurationTopics.createLeafChild("periodicAggregateMetricsIntervalSeconds").withValue(100);
        configurationTopics.createLeafChild("periodicPublishMetricsIntervalSeconds").withValue(300);
        when(mockDeviceConfiguration.getTelemetryConfigurationTopics()).thenReturn(configurationTopics);

        telemetryAgent = spy(new TelemetryAgent(config, mockMqttClient, mockDeviceConfiguration, ma, sme, kme, ses, executorService));
        telemetryAgent.postInject();

        TimeUnit.SECONDS.sleep(2);
        assertNotNull(telemetryAgent.getPeriodicAggregateMetricsFuture());
        assertNotNull(telemetryAgent.getPeriodicPublishMetricsFuture());
        for (PeriodicMetricsEmitter p : telemetryAgent.getPeriodicMetricsEmitters()) {
            assertNotNull(p.future);
        }

        assertNotNull(telemetryAgent.getCurrentConfiguration());
        assertFalse(telemetryAgent.getCurrentConfiguration().get().isEnabled());
        assertEquals(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC, telemetryAgent.getCurrentConfiguration().get().getPeriodicAggregateMetricsIntervalSeconds());
        assertEquals(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC, telemetryAgent.getCurrentConfiguration().get().getPeriodicPublishMetricsIntervalSeconds());
        assertTrue(telemetryAgent.getPeriodicPublishMetricsFuture().isCancelled());
        assertTrue(telemetryAgent.getPeriodicAggregateMetricsFuture().isCancelled());
    }

    @Test
    void GIVEN_Telemetry_Agent_WHEN_starts_up_THEN_periodically_schedule_operations() {
        long milliSeconds = 4000;
        telemetryAgent.getCurrentConfiguration().set(TelemetryConfiguration.builder()
                .periodicPublishMetricsIntervalSeconds(3)
                .periodicAggregateMetricsIntervalSeconds(5)
                .build());

        telemetryAgent.schedulePeriodicAggregateMetrics(false);
        // aggregation starts at 5th second but we are checking only for 3 seconds
        verify(ma, timeout(milliSeconds).times(0)).aggregateMetrics(anyLong(), anyLong());
        verify(ma, timeout(milliSeconds).atLeastOnce()).getMetricsToPublish(anyLong(), anyLong());
        telemetryAgent.getCurrentConfiguration().set(TelemetryConfiguration.builder()
                .periodicPublishMetricsIntervalSeconds(3)
                .periodicAggregateMetricsIntervalSeconds(2)
                .build());
        telemetryAgent.schedulePeriodicAggregateMetrics(false);
        // aggregation starts at least at the 2nd sec
        verify(ma, timeout(milliSeconds).atLeastOnce()).aggregateMetrics(anyLong(), anyLong());
    }

    @Test
    void GIVEN_Telemetry_Agent_WHEN_mqtt_is_interrupted_THEN_aggregation_continues_but_publishing_stops() {
        doReturn(1).when(DEFAULT_HANDLER)
                .retrieveWithDefault(any(), eq(TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC), any());
        doReturn(2).when(DEFAULT_HANDLER)
                .retrieveWithDefault(any(), eq(TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC), any());
        TestFeatureParameters.internalEnableTestingFeatureParameters(DEFAULT_HANDLER);
        Map<Long, List<AggregatedNamespaceData>> metricsToPublishMap = new HashMap<>();
        List<AggregatedNamespaceData> data = new ArrayList<>();
        data.add(AggregatedNamespaceData.builder().namespace("SomeNameSpace").build());
        when(ma.getMetricsToPublish(anyLong(), publishTimeArgumentCaptor.capture())).thenAnswer(invocation -> {
            metricsToPublishMap.put(publishTimeArgumentCaptor.getValue(), data);
            return metricsToPublishMap;
        });

        telemetryAgent.postInject();
        long timeoutMs = 5000;
        verify(mockMqttClient, timeout(timeoutMs).atLeastOnce()).publish(publishRequestArgumentCaptor.capture());
        PublishRequest request = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, request.getQos());
        assertEquals("$aws/things/testThing/greengrass/health/json", request.getTopic());
        reset(mockMqttClient);
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);
        //verify that nothing is published when mqtt is interrupted
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
        // aggregation is continued irrespective of the mqtt connection
        verify(ma, timeout(timeoutMs).atLeastOnce()).aggregateMetrics(anyLong(), anyLong());
    }
}
