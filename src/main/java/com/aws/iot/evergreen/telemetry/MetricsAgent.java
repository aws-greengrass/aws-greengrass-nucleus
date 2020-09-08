/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.MqttChunkedPayloadPublisher;
import lombok.Getter;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

@ImplementsService(name = MetricsAgent.METRICS_AGENT_SERVICE_TOPICS, version = "1.0.0", autostart = true)
public class MetricsAgent extends EvergreenService {
    public static final String METRICS_AGENT_SERVICE_TOPICS = "MetricsAgent";
    public static final String DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC =
            "$aws/things/{thingName}/evergreen/health/json";
    static final String TELEMETRY_METRICS_PUBLISH_TOPICS = "telemetryMetricsPublishTopic";
    static final String TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC = "periodicPublishMetricsIntervalSec";
    static final String TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC = "lastPeriodicPublishMetricsTime";
    public static final String TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC = "periodicAggregateMetricsIntervalSec";
    static final String TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC = "lastPeriodicAggregationMetricsTime";
    private static final int DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC = 180;
    public static final int DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC = 60;
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;
    ScheduledFuture<?> periodicPublishMetricsFuture = null;
    ScheduledFuture<?> periodicAggregateMetricsFuture = null;
    ScheduledFuture<?> periodicSystemMetricsFuture = null;
    private final MqttClient mqttClient;
    private final Topics topics;
    private String updateTopic;
    private String thingName;
    private static int periodicPublishMetricsIntervalSec = 0;
    private static int periodicAggregateMetricsIntervalSec = 0;
    private String telemetryMetricsPublishTopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
    private final SystemMetricsEmitter systemMetricsEmitter = new SystemMetricsEmitter();
    private final MetricsAggregator metricsAggregator = new MetricsAggregator();
    private final MetricsUploader metricsUploader = new MetricsUploader();
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final Object periodicPublishMetricsInProgressLock = new Object();
    private final Object periodicAggregateMetricsInProgressLock = new Object();
    private final MqttChunkedPayloadPublisher<MetricsAggregator.AggregatedMetric> publisher;
    private final ScheduledExecutorService ses = getContext().get(ScheduledExecutorService.class);
    @Getter
    private static long lastPeriodicAggregationMetricsTime = Instant.now().toEpochMilli();


    @Getter
    public MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            isConnected.set(false);
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            isConnected.set(true);
            schedulePeriodicPublishMetrics(true);
        }
    };

    /**
     *  Constructor for metrics agent.
     * @param topics root configuration topic for this service
     * @param mqttClient {@link MqttClient}
     * @param deviceConfiguration {@link DeviceConfiguration}
     */
    @Inject
    public MetricsAgent(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration) {
        super(topics);
        this.topics = topics;
        this.mqttClient = mqttClient;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        updateThingNameAndPublishTopic(Coerce.toString(deviceConfiguration.getThingName()));
    }

    /**
     * Schedules the aggregation of metrics based on the configured aggregation interval.
     */
    private void schedulePeriodicAggregateMetrics(boolean isReconfigured) {
        // If the aggregation interval is reconfigured, cancel the previously scheduled job.
        if (periodicAggregateMetricsFuture != null) {
            periodicAggregateMetricsFuture.cancel(false);
        }
        // System metrics are emitted based on the aggregation interval.
        if (periodicSystemMetricsFuture != null) {
            periodicSystemMetricsFuture.cancel(false);
        }
        if (isReconfigured) {
            synchronized (periodicAggregateMetricsInProgressLock) {
                Instant lastPeriodicAggTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicAggregateTimeTopic()));
                if (lastPeriodicAggTime.plusSeconds(periodicAggregateMetricsIntervalSec).isBefore(Instant.now())) {
                    emitPeriodicSystemMetrics();
                    aggregatePeriodicMetrics();
                }
            }
        }
        periodicSystemMetricsFuture = ses.scheduleWithFixedDelay(
                this::emitPeriodicSystemMetrics, 0, periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
        periodicAggregateMetricsFuture = ses.scheduleWithFixedDelay(
                this::aggregatePeriodicMetrics, 0, periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
    }

    /**
     *  Schedules the publishing of metrics based on the configured publish interval or the mqtt connection status.
     */
    private void schedulePeriodicPublishMetrics(boolean isReconfiguredOrConnectionResumed) {
        // If we missed to publish the metrics due to connection lost or if the publish interval is reconfigured,
        // cancel the previously scheduled job.
        if (periodicPublishMetricsFuture != null) {
            periodicPublishMetricsFuture.cancel(false);
        }
        if (isReconfiguredOrConnectionResumed) {
            synchronized (periodicPublishMetricsInProgressLock) {
                Instant lastPeriodicPublishTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicPublishTimeTopic()));
                if (lastPeriodicPublishTime.plusSeconds(periodicPublishMetricsIntervalSec)
                        .isBefore(Instant.now())) {
                    publishPeriodicMetrics();
                }
            }
        }
        periodicPublishMetricsFuture = ses.scheduleWithFixedDelay(
                this::publishPeriodicMetrics, 0, periodicPublishMetricsIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Helper for metrics aggregator. Also used in tests.
     */
    public void aggregatePeriodicMetrics() {
        long timeStamp = Instant.now().toEpochMilli();
        this.metricsAggregator.aggregateMetrics(periodicAggregateMetricsIntervalSec,timeStamp);
        getPeriodicAggregateTimeTopic().withValue(timeStamp);
    }

    /**
     * Helper for metrics uploader. Also used in tests.
     */
    public void publishPeriodicMetrics() {
        if (!isConnected.get()) {
            logger.atInfo().log("Cannot publish the metrics: MQTT Connection interrupted.");
            return;
        }
        long timeStamp = Instant.now().toEpochMilli();
        Map<Long, List<MetricsAggregator.AggregatedMetric>> metricsToPublishMap =
                this.metricsUploader.getAggregatedMetrics(periodicPublishMetricsIntervalSec, timeStamp);
        getPeriodicPublishTimeTopic().withValue(timeStamp);
        MetricsPayload aggregatedMetricsChunk = MetricsPayload.builder()
                .schema("schema")
                .build();
        this.publisher.publish(aggregatedMetricsChunk, metricsToPublishMap.get(timeStamp));
    }

    /**
     * Helper for system metrics emitter. Also used in tests.
     */
    public void emitPeriodicSystemMetrics() {
        this.systemMetricsEmitter.emitMetrics();
    }

    private Topic getPeriodicPublishTimeTopic() {
        return config.lookup(TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC).dflt(Instant.now().toEpochMilli())
                .subscribe((why, newv) -> lastPeriodicAggregationMetricsTime = Coerce.toLong(newv));
    }

    private Topic getPeriodicAggregateTimeTopic() {
        return config.lookup(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC).dflt(Instant.now().toEpochMilli());
    }

    private void updateThingNameAndPublishTopic(String newThingName) {
        if (newThingName != null) {
            thingName = newThingName;
            updateTopic = telemetryMetricsPublishTopic.replace("{thingName}", thingName);
            this.publisher.setUpdateTopic(updateTopic);
        }
    }

    @Override
    public void startup() throws InterruptedException {
        super.startup();
        topics.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicAggregateMetricsIntervalSec = Coerce.toInt(newv);
                    if (periodicAggregateMetricsFuture != null) {
                        schedulePeriodicAggregateMetrics(true);
                    }
                });
        topics.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicPublishMetricsIntervalSec = Coerce.toInt(newv);
                    if (periodicPublishMetricsFuture != null) {
                        schedulePeriodicPublishMetrics(true);
                    }
                });
        topics.lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_METRICS_PUBLISH_TOPICS)
                .dflt(DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC)
                .subscribe((why, newv) -> telemetryMetricsPublishTopic = Coerce.toString(newv));
        topics.lookup(DeviceConfiguration.DEVICE_PARAM_THING_NAME)
                .subscribe((why, node) -> updateThingNameAndPublishTopic(Coerce.toString(node)));
        this.systemMetricsEmitter.collectSystemMetrics();
        this.schedulePeriodicAggregateMetrics(false);
        this.schedulePeriodicPublishMetrics(false);
        this.mqttClient.addToCallbackEvents(callbacks);
    }

    @Override
    public void shutdown() {
        if (periodicSystemMetricsFuture != null) {
            periodicSystemMetricsFuture.cancel(true);
        }
        if (periodicPublishMetricsFuture != null) {
            periodicPublishMetricsFuture.cancel(true);
        }
        if (periodicAggregateMetricsFuture != null) {
            periodicAggregateMetricsFuture.cancel(true);
        }
    }
}
