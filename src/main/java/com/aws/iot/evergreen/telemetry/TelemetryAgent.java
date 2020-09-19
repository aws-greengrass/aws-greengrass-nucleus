/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.KernelMetricsEmitter;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.MqttChunkedPayloadPublisher;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.lang3.RandomUtils;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;

@ImplementsService(name = TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS, version = "1.0.0", autostart = true)
public class TelemetryAgent extends EvergreenService {
    public static final String TELEMETRY_AGENT_SERVICE_TOPICS = "TelemetryAgent";
    public static final String DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC =
            "$aws/things/{thingName}/greengrass/health/json";
    public static final String TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC = "periodicAggregateMetricsIntervalSec";
    public static final String TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC = "periodicPublishMetricsIntervalSec";
    public static final String TELEMETRY_METRICS_PUBLISH_TOPICS = "telemetryMetricsPublishTopic";
    static final int DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC = 3_600;
    static final String TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC = "lastPeriodicPublishMetricsTime";
    static final String TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC = "lastPeriodicAggregationMetricsTime";
    private static final int DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC = 86_400;
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;
    private static int periodicPublishMetricsIntervalSec = 0;
    private static int periodicAggregateMetricsIntervalSec = 0;
    private final MqttClient mqttClient;
    private final MetricsAggregator metricsAggregator;
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final Object periodicPublishMetricsInProgressLock = new Object();
    private final Object periodicAggregateMetricsInProgressLock = new Object();
    private final MqttChunkedPayloadPublisher<MetricsAggregator.AggregatedMetric> publisher;
    private final ScheduledExecutorService ses;
    @Getter(AccessLevel.PACKAGE)
    private final List<PeriodicMetricsEmitter> periodicMetricsEmitters = new ArrayList<>();
    @Getter(AccessLevel.PACKAGE)
    private ScheduledFuture<?> periodicAggregateMetricsFuture = null;
    @Getter //used in e2e
    private ScheduledFuture<?> periodicPublishMetricsFuture = null;
    private final MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
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
    private String updateTopic;
    private String thingName;
    private String telemetryMetricsPublishTopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;

    /**
     * Constructor for the class.
     * @param topics                root configuration topic for this service
     * @param mqttClient            {@link MqttClient}
     * @param deviceConfiguration   {@link DeviceConfiguration}
     * @param ma                    {@link MetricsAggregator}
     * @param sme                   {@link SystemMetricsEmitter}
     * @param kme                   {@link KernelMetricsEmitter}
     * @param ses                   {@link ScheduledExecutorService}
     */
    @Inject
    public TelemetryAgent(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration,
                          MetricsAggregator ma, SystemMetricsEmitter sme, KernelMetricsEmitter kme,
                          ScheduledExecutorService ses) {
        super(topics);
        this.mqttClient = mqttClient;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        this.ses = ses;
        this.metricsAggregator = ma;
        this.thingName = Coerce.toString(deviceConfiguration.getThingName());
        periodicMetricsEmitters.add(sme);
        periodicMetricsEmitters.add(kme);
        getPeriodicAggregateTimeTopic();
        getPeriodicPublishTimeTopic();
    }

    /**
     * Schedules the aggregation of metrics based on the configured aggregation interval.
     *
     * @param isReconfigured will be true if aggregation interval is reconfigured
     */
    private void schedulePeriodicAggregateMetrics(boolean isReconfigured) {
        for (PeriodicMetricsEmitter emitter : periodicMetricsEmitters) {
            cancelJob(emitter.future, periodicAggregateMetricsInProgressLock, false);
        }
        cancelJob(periodicAggregateMetricsFuture, periodicAggregateMetricsInProgressLock, false);
        if (isReconfigured) {
            synchronized (periodicAggregateMetricsInProgressLock) {
                Instant lastPeriodicAggTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicAggregateTimeTopic()));
                if (lastPeriodicAggTime.plusSeconds(periodicAggregateMetricsIntervalSec).isBefore(Instant.now())) {
                    for (PeriodicMetricsEmitter periodicMetricsEmitter : periodicMetricsEmitters) {
                        periodicMetricsEmitter.emitMetrics();
                    }
                    aggregatePeriodicMetrics();
                }
            }
        }
        synchronized (periodicAggregateMetricsInProgressLock) {
            for (PeriodicMetricsEmitter emitter : periodicMetricsEmitters) {
                // Start emitting metrics with no delay. This is device specific where metrics are stored in files.
                emitter.future = ses.scheduleWithFixedDelay(emitter::emitMetrics, 0,
                        periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
            }

            // As the time based metrics (eg. System metrics) are emitted with the same interval as aggregation, start
            // aggregating the metrics after at least one data point is emitted. So, the initial delay to aggregate the
            // metrics can be made equal to the interval. This is device specific where the metrics are stored in files.
            periodicAggregateMetricsFuture = ses.scheduleWithFixedDelay(this::aggregatePeriodicMetrics,
                    periodicAggregateMetricsIntervalSec, periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
        }
    }

    /**
     * Schedules the publishing of metrics based on the configured publish interval or the mqtt connection status.
     *
     * @param isReconfiguredOrConnectionResumed will be true if the publish interval is reconfigured or when
     *                                          the mqtt connection is resumed.
     */
    //TODO : Publish accumulated data point for each namespace.
    private void schedulePeriodicPublishMetrics(boolean isReconfiguredOrConnectionResumed) {
        // If we missed to publish the metrics due to connection loss or if the publish interval is reconfigured,
        // cancel the previously scheduled job.
        cancelJob(periodicPublishMetricsFuture, periodicPublishMetricsInProgressLock, false);
        if (isReconfiguredOrConnectionResumed) {
            synchronized (periodicPublishMetricsInProgressLock) {
                Instant lastPeriodicPubTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicPublishTimeTopic()));
                if (lastPeriodicPubTime.plusSeconds(periodicPublishMetricsIntervalSec).isBefore(Instant.now())) {
                    publishPeriodicMetrics();
                }
            }
        }
        // Add some jitter as an initial delay. If the fleet has a lot of devices associated to it, we don't want
        // all the devices to send the periodic publish of metrics at the same time.
        long initialDelay = RandomUtils.nextLong(1, periodicPublishMetricsIntervalSec + 1);
        synchronized (periodicPublishMetricsInProgressLock) {
            periodicPublishMetricsFuture = ses.scheduleWithFixedDelay(this::publishPeriodicMetrics, initialDelay,
                    periodicPublishMetricsIntervalSec, TimeUnit.SECONDS);
        }
    }

    /**
     * Helper for metrics aggregator. Also used in tests.
     */
    void aggregatePeriodicMetrics() {
        long timestamp = Instant.now().toEpochMilli();
        long lastAgg = Coerce.toLong(getPeriodicAggregateTimeTopic());
        metricsAggregator.aggregateMetrics(lastAgg, timestamp);
        getPeriodicAggregateTimeTopic().withValue(timestamp);
    }

    /**
     * Helper for metrics uploader. Also used in tests.
     */
    void publishPeriodicMetrics() {
        if (!isConnected.get()) {
            logger.atDebug().log("Cannot publish the metrics. MQTT connection interrupted.");
            return;
        }
        long timestamp = Instant.now().toEpochMilli();
        long lastPublish = Coerce.toLong(getPeriodicPublishTimeTopic());
        Map<Long, List<MetricsAggregator.AggregatedMetric>> metricsToPublishMap =
                metricsAggregator.getMetricsToPublish(lastPublish, timestamp);
        getPeriodicPublishTimeTopic().withValue(timestamp);
        // Publish only if the collected metrics are not empty.
        if (!metricsToPublishMap.get(timestamp).isEmpty()) {
            publisher.publish(MetricsPayload.builder().build(), metricsToPublishMap.get(timestamp));
        }
    }

    private Topic getPeriodicPublishTimeTopic() {
        return getRuntimeConfig().lookup(TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC)
                .dflt(Instant.now().toEpochMilli());
    }

    private Topic getPeriodicAggregateTimeTopic() {
        return getRuntimeConfig().lookup(TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC)
                .dflt(Instant.now().toEpochMilli());
    }

    private void updateThingNameAndPublishTopic(String newThingName) {
        if (newThingName != null) {
            thingName = newThingName;
            updateTopic = telemetryMetricsPublishTopic.replace("{thingName}", thingName);
            publisher.setUpdateTopic(updateTopic);
        }
    }

    private void cancelJob(ScheduledFuture<?> future, Object lock, boolean immediately) {
        synchronized (lock) {
            if (future != null) {
                future.cancel(immediately);
            }
        }
    }

    @Override
    public void startup() throws InterruptedException {
        getRuntimeConfig().lookup(TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicAggregateMetricsIntervalSec = Coerce.toInt(newv);
                    synchronized (periodicAggregateMetricsInProgressLock) {
                        if (periodicAggregateMetricsFuture != null) {
                            schedulePeriodicAggregateMetrics(true);
                        }
                    }
                });
        getRuntimeConfig().lookup(TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicPublishMetricsIntervalSec = Coerce.toInt(newv);
                    synchronized (periodicPublishMetricsInProgressLock) {
                        if (periodicPublishMetricsFuture != null) {
                            schedulePeriodicPublishMetrics(true);
                        }
                    }
                });
        getRuntimeConfig().lookup(TELEMETRY_METRICS_PUBLISH_TOPICS)
                .dflt(DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC)
                .subscribe((why, newv) -> {
                    telemetryMetricsPublishTopic = Coerce.toString(newv);
                    //update topic with the existing thing name
                    updateThingNameAndPublishTopic(thingName);
                });
        config.lookup(DeviceConfiguration.DEVICE_PARAM_THING_NAME)
                .subscribe((why, node) -> updateThingNameAndPublishTopic(Coerce.toString(node)));
        updateThingNameAndPublishTopic(thingName);
        schedulePeriodicAggregateMetrics(false);
        schedulePeriodicPublishMetrics(false);
        mqttClient.addToCallbackEvents(callbacks);
        super.startup();
    }

    @Override
    public void shutdown() {
        for (PeriodicMetricsEmitter emitter : periodicMetricsEmitters) {
            cancelJob(emitter.future, periodicAggregateMetricsInProgressLock, true);
        }
        cancelJob(periodicAggregateMetricsFuture, periodicAggregateMetricsInProgressLock, true);
        cancelJob(periodicPublishMetricsFuture, periodicPublishMetricsInProgressLock, true);
    }
}
