/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelMetricsEmitter;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.MqttChunkedPayloadPublisher;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.lang3.RandomUtils;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;

import javax.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@ImplementsService(name = MetricsAgent.METRICS_AGENT_SERVICE_TOPICS, version = "1.0.0", autostart = true)
public class MetricsAgent extends EvergreenService {
    public static final String METRICS_AGENT_SERVICE_TOPICS = "MetricsAgent";
    public static final String DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC =
            "$aws/things/{thingName}/greengrassv2/health/json";
    @Getter // Used in e2e
    private static final String TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC = "periodicAggregateMetricsIntervalSec";
    @Getter(AccessLevel.PACKAGE)
    private static final int DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC = 3_600;
    @Getter(AccessLevel.PACKAGE)
    private static final String TELEMETRY_METRICS_PUBLISH_TOPICS = "telemetryMetricsPublishTopic";
    @Getter // Used in e2e
    private static final String TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC = "periodicPublishMetricsIntervalSec";
    @Getter(AccessLevel.PACKAGE)
    private static final String TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC = "lastPeriodicPublishMetricsTime";
    @Getter(AccessLevel.PACKAGE)
    private static final String TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC = "lastPeriodicAggregationMetricsTime";
    private static final int DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC = 86_400;
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;
    private static int periodicPublishMetricsIntervalSec = 0;
    private static int periodicAggregateMetricsIntervalSec = 0;
    private final MqttClient mqttClient;
    private final MetricsAggregator metricsAggregator = new MetricsAggregator();
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final Object periodicPublishMetricsInProgressLock = new Object();
    private final Object periodicAggregateMetricsInProgressLock = new Object();
    private final MqttChunkedPayloadPublisher<MetricsAggregator.AggregatedMetric> publisher;
    private final ScheduledExecutorService ses;
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
    private ArrayList<PeriodicMetricsEmitter> periodicMetricsEmitters = new ArrayList<>();
    private String updateTopic;
    private String thingName;
    private String telemetryMetricsPublishTopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;

    /**
     * Constructor for metrics agent.
     *
     * @param topics              root configuration topic for this service
     * @param mqttClient          {@link MqttClient}
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @param kernel              {@link Kernel}
     * @param ses                 {@link ScheduledExecutorService}
     */
    @Inject
    public MetricsAgent(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration, Kernel kernel,
                        ScheduledExecutorService ses) {
        super(topics);
        this.mqttClient = mqttClient;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        this.ses = ses;
        periodicMetricsEmitters.add(new SystemMetricsEmitter());
        periodicMetricsEmitters.add(new KernelMetricsEmitter(kernel));
        getPeriodicAggregateTimeTopic();
        getPeriodicPublishTimeTopic();
        updateThingNameAndPublishTopic(Coerce.toString(deviceConfiguration.getThingName()));
    }

    /**
     * Schedules the aggregation of metrics based on the configured aggregation interval.
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
     */
    private void schedulePeriodicPublishMetrics(boolean isReconfiguredOrConnectionResumed) {
        // If we missed to publish the metrics due to connection loss or if the publish interval is reconfigured,
        // cancelJob the previously scheduled job.
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
        return config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC)
                .dflt(Instant.now().toEpochMilli());
    }

    private Topic getPeriodicAggregateTimeTopic() {
        return config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC)
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
        if (future != null) {
            synchronized (lock) {
                future.cancel(immediately);
            }
        }
    }

    @Override
    public void startup() throws InterruptedException {
        for (PeriodicMetricsEmitter emitter : periodicMetricsEmitters) {
            emitter.buildMetrics();
        }
        config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicAggregateMetricsIntervalSec = Coerce.toInt(newv);
                    synchronized (periodicAggregateMetricsInProgressLock) {
                        if (periodicAggregateMetricsFuture != null) {
                            schedulePeriodicAggregateMetrics(true);
                        }
                    }
                });
        config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicPublishMetricsIntervalSec = Coerce.toInt(newv);
                    synchronized (periodicPublishMetricsInProgressLock) {
                        if (periodicPublishMetricsFuture != null) {
                            schedulePeriodicPublishMetrics(true);
                        }
                    }
                });
        config.lookup(RUNTIME_STORE_NAMESPACE_TOPIC, TELEMETRY_METRICS_PUBLISH_TOPICS)
                .dflt(DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC)
                .subscribe((why, newv) -> telemetryMetricsPublishTopic = Coerce.toString(newv));
        config.lookup(DeviceConfiguration.DEVICE_PARAM_THING_NAME)
                .subscribe((why, node) -> updateThingNameAndPublishTopic(Coerce.toString(node)));
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
