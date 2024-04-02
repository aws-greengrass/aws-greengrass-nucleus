/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.KernelMetricsEmitter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.MqttChunkedPayloadPublisher;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import org.apache.commons.lang3.RandomUtils;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import javax.inject.Inject;

@ImplementsService(name = TelemetryAgent.TELEMETRY_AGENT_SERVICE_TOPICS, autostart = true)
public class TelemetryAgent extends GreengrassService {
    public static final String TELEMETRY_AGENT_SERVICE_TOPICS = "TelemetryAgent";
    public static final String DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC =
            "$aws/things/{thingName}/greengrass/health/json";
    public static final String TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC
            = "telemetryPeriodicAggregateMetricsIntervalSec";
    public static final String TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC
            = "telemetryPeriodicPublishMetricsIntervalSec";
    public static final String TELEMETRY_LAST_PERIODIC_PUBLISH_TIME_TOPIC = "lastPeriodicPublishMetricsTime";
    public static final String TELEMETRY_LAST_PERIODIC_AGGREGATION_TIME_TOPIC = "lastPeriodicAggregationMetricsTime";
    public static final int DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC = 3_600;
    public static final int DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC = 86_400;
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;
    private final MqttClient mqttClient;
    private final MetricsAggregator metricsAggregator;
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final Lock periodicPublishMetricsInProgressLock = LockFactory.newReentrantLock("publishInProgress");
    private final Lock periodicAggregateMetricsInProgressLock = LockFactory.newReentrantLock("aggInProgress");
    private final MqttChunkedPayloadPublisher<AggregatedNamespaceData> publisher;
    private final ScheduledExecutorService ses;
    private final ExecutorService executorService;
    private final DeviceConfiguration deviceConfiguration;
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
        }
    };
    private String thingName;
    /**
     * Configuration for telemetry.
     */
    @Getter(AccessLevel.PACKAGE) // Needed for unit tests.
    private final AtomicReference<TelemetryConfiguration> currentConfiguration =
            new AtomicReference<>(TelemetryConfiguration.builder().build());

    /**
     * Constructor for the class.
     *
     * @param topics              root configuration topic for this service
     * @param mqttClient          {@link MqttClient}
     * @param deviceConfiguration {@link DeviceConfiguration}
     * @param ma                  {@link MetricsAggregator}
     * @param sme                 {@link SystemMetricsEmitter}
     * @param kme                 {@link KernelMetricsEmitter}
     * @param ses                 {@link ScheduledExecutorService}
     * @param executorService     {@link ExecutorService}
     */
    @Inject
    public TelemetryAgent(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration,
                          MetricsAggregator ma, SystemMetricsEmitter sme, KernelMetricsEmitter kme,
                          ScheduledExecutorService ses, ExecutorService executorService) {
        this(topics, mqttClient, deviceConfiguration, ma, sme, kme, ses, executorService,
                DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC, DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC);
    }

    /**
     * Constructor for the class.
     *
     * @param topics                              root configuration topic for this service
     * @param mqttClient                          {@link MqttClient}
     * @param deviceConfiguration                 {@link DeviceConfiguration}
     * @param ma                                  {@link MetricsAggregator}
     * @param sme                                 {@link SystemMetricsEmitter}
     * @param kme                                 {@link KernelMetricsEmitter}
     * @param ses                                 {@link ScheduledExecutorService}
     * @param executorService                     {@link ExecutorService}
     * @param periodicPublishMetricsIntervalSec   interval for cadence based telemetry publish.
     * @param periodicAggregateMetricsIntervalSec interval for cadence based telemetry metrics aggregation.
     */
    @SuppressWarnings("PMD.ExcessiveParameterList")
    TelemetryAgent(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration,
                   MetricsAggregator ma, SystemMetricsEmitter sme, KernelMetricsEmitter kme,
                   ScheduledExecutorService ses, ExecutorService executorService, int periodicPublishMetricsIntervalSec,
                   int periodicAggregateMetricsIntervalSec) {
        super(topics);
        this.mqttClient = mqttClient;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        this.ses = ses;
        this.executorService = executorService;
        this.metricsAggregator = ma;
        this.deviceConfiguration = deviceConfiguration;
        this.thingName = Coerce.toString(deviceConfiguration.getThingName());
        int finalPeriodicAggregateMetricsIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC, periodicAggregateMetricsIntervalSec).intValue();
        int finalPeriodicPublishMetricsIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC, periodicPublishMetricsIntervalSec).intValue();
        currentConfiguration.set(TelemetryConfiguration.builder()
                .periodicAggregateMetricsIntervalSeconds(finalPeriodicAggregateMetricsIntervalSec)
                .periodicPublishMetricsIntervalSeconds(finalPeriodicPublishMetricsIntervalSec)
                .build());
        periodicMetricsEmitters.add(sme);
        periodicMetricsEmitters.add(kme);
        getPeriodicAggregateTimeTopic();
        getPeriodicPublishTimeTopic();
        schedulePeriodicAggregateMetrics(false);
        // Subscribe to thing name changes.
        deviceConfiguration.getThingName()
                .subscribe((why, node) -> updateThingNameAndPublishTopic(Coerce.toString(node)));

        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            this.isConnected.set(false);
            // Right now the connection cannot be brought online without a restart.
            // Skip setting up scheduled publish because it won't work
            return;
        }

        schedulePeriodicPublishMetrics(false);
    }

    /**
     * Schedules the aggregation of metrics based on the configured aggregation interval.
     *
     * @param isReconfigured will be true if aggregation interval is reconfigured
     */
    void schedulePeriodicAggregateMetrics(boolean isReconfigured) {
        for (PeriodicMetricsEmitter emitter : periodicMetricsEmitters) {
            cancelJob(emitter.future, periodicAggregateMetricsInProgressLock, false);
        }
        cancelJob(periodicAggregateMetricsFuture, periodicAggregateMetricsInProgressLock, false);
        TelemetryConfiguration configuration = currentConfiguration.get();
        // If telemetry is disabled, then return.
        if (!configuration.isEnabled()) {
            return;
        }
        if (isReconfigured) {
            try (LockScope ls = LockScope.lock(periodicAggregateMetricsInProgressLock)) {
                Instant lastPeriodicAggTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicAggregateTimeTopic()));
                if (lastPeriodicAggTime.plusSeconds(configuration.getPeriodicAggregateMetricsIntervalSeconds())
                        .isBefore(Instant.now())) {
                    for (PeriodicMetricsEmitter periodicMetricsEmitter : periodicMetricsEmitters) {
                        periodicMetricsEmitter.emitMetrics();
                    }
                    aggregatePeriodicMetrics();
                }
            }
        }
        int periodicAggregateMetricsIntervalSec = configuration.getPeriodicAggregateMetricsIntervalSeconds();
        try (LockScope ls = LockScope.lock(periodicAggregateMetricsInProgressLock)) {
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
     * @param isReconfigured will be true if the publish interval is reconfigured or when
     *                       the mqtt connection is resumed.
     */
    void schedulePeriodicPublishMetrics(boolean isReconfigured) {
        // If we missed to publish the metrics due to connection loss or if the publish interval is reconfigured,
        // cancel the previously scheduled job.
        cancelJob(periodicPublishMetricsFuture, periodicPublishMetricsInProgressLock, false);
        TelemetryConfiguration configuration = currentConfiguration.get();
        // If telemetry is disabled, then return.
        if (!configuration.isEnabled()) {
            return;
        }
        if (isReconfigured) {
            try (LockScope ls = LockScope.lock(periodicPublishMetricsInProgressLock)) {
                Instant lastPeriodicPubTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicPublishTimeTopic()));
                if (lastPeriodicPubTime.plusSeconds(configuration.getPeriodicPublishMetricsIntervalSeconds())
                        .isBefore(Instant.now())) {
                    publishPeriodicMetrics();
                }
            }
        }
        int periodicPublishMetricsIntervalSec = configuration.getPeriodicPublishMetricsIntervalSeconds();
        // Add some jitter as an initial delay. If the fleet has a lot of devices associated to it, we don't want
        // all the devices to publish metrics at the same time.
        long initialDelay = RandomUtils.nextLong(0, periodicPublishMetricsIntervalSec + 1);
        try (LockScope ls = LockScope.lock(periodicPublishMetricsInProgressLock)) {
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
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    void publishPeriodicMetrics() {
        try {
            if (!isConnected.get()) {
                logger.atDebug().log("Cannot publish the metrics. MQTT connection interrupted.");
                return;
            }
            long timestamp = Instant.now().toEpochMilli();
            long lastPublish = Coerce.toLong(getPeriodicPublishTimeTopic());
            Map<Long, List<AggregatedNamespaceData>> metricsToPublishMap =
                    metricsAggregator.getMetricsToPublish(lastPublish, timestamp);
            getPeriodicPublishTimeTopic().withValue(timestamp);
            if (metricsToPublishMap != null && metricsToPublishMap.containsKey(timestamp)) {
                publisher.publish(MetricsPayload.builder().build(), metricsToPublishMap.get(timestamp));
                logger.atInfo().event("telemetry-metrics-published").log("Telemetry metrics update published.");
            }
        } catch (Throwable t) {
            logger.atWarn().log("Error collecting telemetry. Will retry.", t);
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
            String updateTopic = DEFAULT_TELEMETRY_METRICS_PUBLISH_TOPIC.replace("{thingName}", thingName);
            publisher.setUpdateTopic(updateTopic);
        }
    }

    private void cancelJob(ScheduledFuture<?> future, Lock lock, boolean immediately) {
        try (LockScope ls = LockScope.lock(lock)) {
            if (future != null) {
                future.cancel(immediately);
            }
        }
    }

    @Override
    @SuppressFBWarnings
    public void postInject() {
        executorService.submit(() -> {
            Topics configurationTopics = deviceConfiguration.getTelemetryConfigurationTopics();
            configurationTopics.subscribe((why, newv) -> handleTelemetryConfiguration(configurationTopics));
            handleTelemetryConfiguration(configurationTopics);
            updateThingNameAndPublishTopic(thingName);
            mqttClient.addToCallbackEvents(callbacks);
            TestFeatureParameters.registerHandlerCallback(this.getName(),
                    this::handleTestFeatureParametersHandlerChange);
        });
        super.postInject();
    }

    private void handleTelemetryConfiguration(Topics configurationTopics) {
        TelemetryConfiguration newTelemetryConfiguration =
                TelemetryConfiguration.fromPojo(configurationTopics.toPOJO());
        TelemetryConfiguration configuration = currentConfiguration.get();
        boolean aggregateMetricsIntervalSecChanged = false;
        boolean publishMetricsIntervalSecChanged = false;
        if (newTelemetryConfiguration.isEnabled()) {
            // If the current aggregation interval is different from the new interval, then reschedule
            // the periodic aggregation task
            aggregateMetricsIntervalSecChanged = configuration.getPeriodicAggregateMetricsIntervalSeconds()
                    != newTelemetryConfiguration.getPeriodicAggregateMetricsIntervalSeconds();
            // If the current publish interval is different from the new interval, then reschedule
            // the publish aggregation task
            publishMetricsIntervalSecChanged = configuration.getPeriodicPublishMetricsIntervalSeconds()
                    != newTelemetryConfiguration.getPeriodicPublishMetricsIntervalSeconds();
        } else {
            // If telemetry is not enabled, then cancel the futures.
            cancelAllJobs();
        }
        currentConfiguration.set(newTelemetryConfiguration);
        if (aggregateMetricsIntervalSecChanged) {
            schedulePeriodicAggregateMetrics(true);
        }
        if (publishMetricsIntervalSecChanged) {
            schedulePeriodicPublishMetrics(true);
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleTestFeatureParametersHandlerChange(Boolean isDefault) {
        TelemetryConfiguration configuration = currentConfiguration.get();
        setPeriodicAggregateMetricsIntervalAndSchedule(configuration.getPeriodicPublishMetricsIntervalSeconds());
        setPeriodicPublishMetricsIntervalAndScheduleTask(configuration.getPeriodicAggregateMetricsIntervalSeconds());
    }

    private void setPeriodicPublishMetricsIntervalAndScheduleTask(int defaultValue) {
        TelemetryConfiguration telemetryConfiguration = currentConfiguration.get();
        currentConfiguration.set(TelemetryConfiguration.builder()
                .periodicPublishMetricsIntervalSeconds(TestFeatureParameters
                        .retrieveWithDefault(Double.class, TELEMETRY_TEST_PERIODIC_PUBLISH_INTERVAL_SEC, defaultValue)
                        .intValue())
                .periodicAggregateMetricsIntervalSeconds(telemetryConfiguration
                        .getPeriodicAggregateMetricsIntervalSeconds())
                .enabled(telemetryConfiguration.isEnabled())
                .build());
        try (LockScope ls = LockScope.lock(periodicPublishMetricsInProgressLock)) {
            if (periodicPublishMetricsFuture != null && telemetryConfiguration.isEnabled()) {
                schedulePeriodicPublishMetrics(true);
            }
        }
    }

    private void setPeriodicAggregateMetricsIntervalAndSchedule(int defaultValue) {
        TelemetryConfiguration telemetryConfiguration = currentConfiguration.get();
        currentConfiguration.set(TelemetryConfiguration.builder()
                .periodicAggregateMetricsIntervalSeconds(TestFeatureParameters
                        .retrieveWithDefault(Double.class, TELEMETRY_TEST_PERIODIC_AGGREGATE_INTERVAL_SEC, defaultValue)
                        .intValue())
                .periodicPublishMetricsIntervalSeconds(telemetryConfiguration
                        .getPeriodicPublishMetricsIntervalSeconds())
                .enabled(telemetryConfiguration.isEnabled())
                .build());

        try (LockScope ls = LockScope.lock(periodicAggregateMetricsInProgressLock)) {
            if (periodicAggregateMetricsFuture != null && telemetryConfiguration.isEnabled()) {
                schedulePeriodicAggregateMetrics(true);
            }
        }
    }

    @Override
    public void shutdown() {
        cancelAllJobs();
        TestFeatureParameters.unRegisterHandlerCallback(this.getName());
    }

    private void cancelAllJobs() {
        logger.atInfo().log("Cancelling all telemetry scheduled tasks.");
        for (PeriodicMetricsEmitter emitter : periodicMetricsEmitters) {
            cancelJob(emitter.future, periodicAggregateMetricsInProgressLock, true);
        }
        cancelJob(periodicAggregateMetricsFuture, periodicAggregateMetricsInProgressLock, true);
        cancelJob(periodicPublishMetricsFuture, periodicPublishMetricsInProgressLock, true);
    }
}
