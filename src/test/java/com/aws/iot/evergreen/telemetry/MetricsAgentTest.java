package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logging.impl.config.EvergreenLogConfig;
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
import org.slf4j.event.Level;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.TELEMETRY_METRICS_PUBLISH_TOPICS;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC;
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
    private ScheduledThreadPoolExecutor ses;
    private MetricsAgent metricsAgent;

    @BeforeEach
    public void setup() {
        serviceFullName = "MetricsAgentService";
        initializeMockedConfig();
        ses = new ScheduledThreadPoolExecutor(3);

        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);
        Topic periodicAggregateMetricsIntervalSec = Topic.of(context, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC, "1");
        lenient().when(config.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC))
                .thenReturn(periodicAggregateMetricsIntervalSec);
        Topic periodicPublishMetricsIntervalSec = Topic.of(context, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC, "5");
        lenient().when(config.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC))
                .thenReturn(periodicPublishMetricsIntervalSec);
        Topic lastPeriodicAggregateTime = Topic.of(context, TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC,
                Instant.now().toEpochMilli());
        lenient().when(config.lookup(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC))
                .thenReturn(lastPeriodicAggregateTime);
        Topic lastPeriodicPublishTime = Topic.of(context, TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC,
                Instant.now().toEpochMilli());
        lenient().when(config.lookup(TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC))
                .thenReturn(lastPeriodicPublishTime);
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        when(config.lookup(DEVICE_PARAM_THING_NAME)).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        Topic telemetryMetricsPublishTopic = Topic.of(context, TELEMETRY_METRICS_PUBLISH_TOPICS,
                DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC);
        when(config.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_METRICS_PUBLISH_TOPICS))
                .thenReturn(telemetryMetricsPublishTopic);
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
        metricsAgent = spy(new MetricsAgent(config, mockMqttClient, mockDeviceConfiguration));

    }

    @AfterEach
    public void cleanUp() {
        ses.shutdownNow();
        metricsAgent.shutdown();
    }

    @Test
    public void GIVEN_Metrics_Agent_WHEN_starts_up_THEN_schedule_operations_on_metrics() throws InterruptedException {
        assertNull(metricsAgent.periodicAggregateMetricsFuture);
        assertNull(metricsAgent.periodicSystemMetricsFuture);
        assertNull(metricsAgent.periodicPublishMetricsFuture);

        metricsAgent.startup();
        assertNotNull(metricsAgent.periodicAggregateMetricsFuture);
        assertNotNull(metricsAgent.periodicSystemMetricsFuture);
        assertNotNull(metricsAgent.periodicPublishMetricsFuture);
    }

    @Test
    public void GIVEN_Metrics_Agent_WHEN_starts_up_THEN_periodically_schedule_operations() throws InterruptedException {
        doNothing().when(metricsAgent).aggregatePeriodicMetrics();
        doNothing().when(metricsAgent).emitPeriodicSystemMetrics();

        metricsAgent.startup();
        long milliSeconds = 3000;
        verify(metricsAgent, timeout(milliSeconds).times(3)).aggregatePeriodicMetrics();
        verify(metricsAgent, timeout(milliSeconds).times(3)).emitPeriodicSystemMetrics();
        verify(metricsAgent, timeout(milliSeconds).atLeast(1)).publishPeriodicMetrics();

        reset(metricsAgent);
        Topic periodicAggregateMetricsIntervalSec = Topic.of(context, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC, "5");
        lenient().when(config.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC))
                .thenReturn(periodicAggregateMetricsIntervalSec);
        verify(metricsAgent, timeout(milliSeconds).times(0)).aggregatePeriodicMetrics();
        verify(metricsAgent, timeout(milliSeconds).times(0)).emitPeriodicSystemMetrics();
    }


    @Test
    public void GIVEN_Metrics_Agent_WHEN_mqtt_is_interrupted_THEN_aggregation_continues_but_publishing_stops()
            throws InterruptedException {
        EvergreenLogConfig.getInstance().setLevel(Level.TRACE);
        Topic periodicPublishMetricsIntervalSec = Topic.of(context, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC, "2");
        lenient().when(config.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC))
                .thenReturn(periodicPublishMetricsIntervalSec);
        doNothing().when(mockMqttClient).addToCallbackEvents(mqttClientConnectionEventsArgumentCaptor.capture());
        metricsAgent.startup();
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionInterrupted(500);
        //verify that nothing is published when mqtt is interrupted
        verify(mockMqttClient, times(0)).publish(publishRequestArgumentCaptor.capture());
        long milliSeconds = 2000;
        verify(metricsAgent, timeout(milliSeconds).atLeast(2)).aggregatePeriodicMetrics();
        //verify that metrics are published atleast once in the periodic interval when the connection resumes
        mqttClientConnectionEventsArgumentCaptor.getValue().onConnectionResumed(true);
        verify(mockMqttClient, timeout(milliSeconds).atLeast(1)).publish(publishRequestArgumentCaptor.capture());
        PublishRequest request = publishRequestArgumentCaptor.getValue();
        assertEquals(QualityOfService.AT_LEAST_ONCE, request.getQos());
        assertEquals("$aws/things/testThing/evergreen/health/json", request.getTopic());
    }
}

