/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import org.apache.commons.lang3.RandomUtils;
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
    @Getter
    private static final String TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC = "periodicAggregateMetricsIntervalSec";
    @Getter
    private static final int DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC = 30;
    @Getter
    private static final String TELEMETRY_METRICS_PUBLISH_TOPICS = "telemetryMetricsPublishTopic";
    @Getter
    private  static final String TELEMETRY_PERIODIC_PUBLISH_INTERVAL_SEC = "periodicPublishMetricsIntervalSec";
    @Getter
    private static final String TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC = "lastPeriodicPublishMetricsTime";
    @Getter
    private static final String TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC = "lastPeriodicAggregationMetricsTime";
    private static final int DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC = 120;
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;
    private static int periodicPublishMetricsIntervalSec = 0;
    private static int periodicAggregateMetricsIntervalSec = 0;
    private final MqttClient mqttClient;
    private final Topics topics;
    private final SystemMetricsEmitter systemMetricsEmitter = new SystemMetricsEmitter();
    private final MetricsAggregator metricsAggregator = new MetricsAggregator();
    private final MetricsUploader metricsUploader = new MetricsUploader();
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final Object periodicPublishMetricsInProgressLock = new Object();
    private final Object periodicAggregateMetricsInProgressLock = new Object();
    private final MqttChunkedPayloadPublisher<MetricsAggregator.AggregatedMetric> publisher;
    private final ScheduledExecutorService ses;
    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
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
    @Getter
    private ScheduledFuture<?> periodicAggregateMetricsFuture = null;
    @Getter
    private ScheduledFuture<?> periodicEmitSystemMetricsFuture = null;
    @Getter
    private ScheduledFuture<?> periodicEmitKernelMetricsFuture = null;
    @Getter
    private ScheduledFuture<?> periodicPublishMetricsFuture = null;
    private String updateTopic;
    private String thingName;
    private String telemetryMetricsPublishTopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC;
    private final KernelMetricsEmitter kernelMetricsEmitter;
    private final MetricsPayload aggregatedMetricsChunk = MetricsPayload.builder().schema("2020-07-30").build();

    /**
     * Constructor for metrics agent.
     *
     * @param topics              root configuration topic for this service
     * @param mqttClient          {@link MqttClient}
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @param ses {@link ScheduledExecutorService}
     * @param kernelMetricsEmitter {@link KernelMetricsEmitter}
     */
    @Inject
    public MetricsAgent(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration,
                        ScheduledExecutorService ses, KernelMetricsEmitter kernelMetricsEmitter) {
        super(topics);
        this.topics = topics;
        this.mqttClient = mqttClient;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        this.ses = ses;
        this.kernelMetricsEmitter = kernelMetricsEmitter;
        getPeriodicAggregateTimeTopic();
        getPeriodicPublishTimeTopic();
        updateThingNameAndPublishTopic(Coerce.toString(deviceConfiguration.getThingName()));

    }

    /**
     * Schedules the aggregation of metrics based on the configured aggregation interval.
     */
    private void schedulePeriodicAggregateMetrics(boolean isReconfigured) {
        synchronized (periodicAggregateMetricsInProgressLock) {
            // If the aggregation interval is reconfigured, cancel the previously scheduled job.
            if (periodicAggregateMetricsFuture != null) {
            periodicAggregateMetricsFuture.cancel(false);
            }
            // These are emitted based on the aggregation interval.
            if (periodicEmitSystemMetricsFuture != null) {
                periodicEmitSystemMetricsFuture.cancel(false);
            }
            // These are emitted based on the aggregation interval.
            if (periodicEmitKernelMetricsFuture != null) {
                periodicEmitKernelMetricsFuture.cancel(false);
            }
        }
        if (isReconfigured) {
            synchronized (periodicAggregateMetricsInProgressLock) {
                Instant lastPeriodicAggTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicAggregateTimeTopic()));
                if (lastPeriodicAggTime.plusSeconds(periodicAggregateMetricsIntervalSec).isBefore(Instant.now())) {
                    emitPeriodicSystemMetrics();
                    emitPeriodicKernelMetrics();
                    aggregatePeriodicMetrics();
                }
            }
        }
        // Start emitting metrics with no delay. This is device specific where metrics are stored in files.
        periodicEmitSystemMetricsFuture = ses.scheduleWithFixedDelay(this::emitPeriodicSystemMetrics, 0,
                    periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
        periodicEmitKernelMetricsFuture = ses.scheduleWithFixedDelay(this::emitPeriodicKernelMetrics, 0,
                periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
        // As the time based metrics (eg. System metrics) are emitted with the same interval as aggregation, start
        // aggregating the metrics after at least one data point is emitted. So, the initial delay to aggregate the
        // metrics can be made equal to the interval. This is device specific where the metrics are stored in files.
        periodicAggregateMetricsFuture = ses.scheduleWithFixedDelay(this::aggregatePeriodicMetrics,
                periodicAggregateMetricsIntervalSec, periodicAggregateMetricsIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Schedules the publishing of metrics based on the configured publish interval or the mqtt connection status.
     */
    private void schedulePeriodicPublishMetrics(boolean isReconfiguredOrConnectionResumed) {
        // If we missed to publish the metrics due to connection loss or if the publish interval is reconfigured,
        // cancel the previously scheduled job.
        synchronized (periodicPublishMetricsInProgressLock) {
            if (periodicPublishMetricsFuture != null) {
                periodicPublishMetricsFuture.cancel(false);
            }
        }
        if (isReconfiguredOrConnectionResumed) {
            synchronized (periodicPublishMetricsInProgressLock) {
                Instant lastPeriodicPubTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicPublishTimeTopic()));
                if (lastPeriodicPubTime.plusSeconds(periodicPublishMetricsIntervalSec)
                        .isBefore(Instant.now())) {
                    publishPeriodicMetrics();
                }
            }
        }
        // Add some jitter as an initial delay. If the fleet has a lot of devices associated to it, we don't want
        // all the devices to send the periodic publish of metrics at the same time.
        long initialDelay = RandomUtils.nextLong(0, periodicPublishMetricsIntervalSec);
        periodicPublishMetricsFuture = ses.scheduleWithFixedDelay(this::publishPeriodicMetrics, initialDelay,
                periodicPublishMetricsIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Helper for metrics aggregator. Also used in tests.
     */
    public void aggregatePeriodicMetrics() {
        long timeStamp = Instant.now().toEpochMilli();
        long lastAgg = Coerce.toLong(getPeriodicAggregateTimeTopic());
        this.metricsAggregator.aggregateMetrics(lastAgg, timeStamp);
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
        long lastPublish = Coerce.toLong(getPeriodicPublishTimeTopic());
        Map<Long, List<MetricsAggregator.AggregatedMetric>> metricsToPublishMap =
                this.metricsUploader.getAggregatedMetrics(lastPublish, timeStamp);
        getPeriodicPublishTimeTopic().withValue(timeStamp);
        try {
            logger.atInfo().log(new ObjectMapper().writeValueAsString(metricsToPublishMap.get(timeStamp)));
        } catch (JsonProcessingException e) {
            logger.atTrace().log("Invalid message format", e);
        }
        // Publish only if the collected metrics are not empty.
        if (!metricsToPublishMap.get(timeStamp).isEmpty()) {
            this.publisher.publish(aggregatedMetricsChunk, metricsToPublishMap.get(timeStamp));
        }
    }

    /**
     * Helper for system metrics emitter. Also used in tests.
     */
    public void emitPeriodicSystemMetrics() {
        this.systemMetricsEmitter.emitMetrics();
    }

    /**
     * Helper for system metrics emitter. Also used in tests.
     */
    public void emitPeriodicKernelMetrics() {
        this.kernelMetricsEmitter.emitMetrics();
    }

    private Topic getPeriodicPublishTimeTopic() {
        return config.lookup(TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC).dflt(Instant.now().toEpochMilli());
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
        this.systemMetricsEmitter.collectSystemMetrics();
        this.kernelMetricsEmitter.collectKernelComponentState();
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
        this.schedulePeriodicAggregateMetrics(false);
        this.schedulePeriodicPublishMetrics(false);
        this.mqttClient.addToCallbackEvents(callbacks);
        super.startup();
    }

    @Override
    public void shutdown() {
        if (periodicEmitSystemMetricsFuture != null) {
            periodicEmitSystemMetricsFuture.cancel(true);
        }
        if (periodicEmitKernelMetricsFuture != null) {
            periodicEmitKernelMetricsFuture.cancel(true);
        }
        if (periodicPublishMetricsFuture != null) {
            periodicPublishMetricsFuture.cancel(true);
        }
        if (periodicAggregateMetricsFuture != null) {
            periodicAggregateMetricsFuture.cancel(true);
        }
    }
}
