/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.MetricsAgent;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import com.aws.iot.evergreen.util.Coerce;
import lombok.NonNull;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC;
import static com.aws.iot.evergreen.telemetry.MetricsAgent.TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC;

public class KernelMetricsEmitter {
    // Kernel metrics
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String KERNEL_COMPONENT_METRIC_STORE = TelemetryNamespace.KernelComponents.toString();
    private static Map<TelemetryMetricName, MetricDataBuilder> kernelMetrics = new HashMap<>();
    private static Map<TelemetryMetricName, Integer> kernelMetricsData = new HashMap<>();
    private ScheduledFuture<?> periodicKernelMetricsFuture = null;
    private final Object periodicKernelMetricsInProgressLock = new Object();
    private int periodicKernelMetricsIntervalSec = 0;

    @NonNull
    private final Kernel kernel;

    /**
     *  Constructor for kernel metrics emitter.
     * @param kernel  {@link Kernel}
     */
    public KernelMetricsEmitter(Kernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Schedules the periodic kernel metrics based on the configured aggregation interval.
     * @param isReconfigured true if the interval is reconfigured
     */
    public void schedulePeriodicKernelMetrics(boolean isReconfigured) {
        // If the kernel/aggregation interval is reconfigured, cancel the previously scheduled job.
        if (this.periodicKernelMetricsFuture != null) {
            this.periodicKernelMetricsFuture.cancel(false);
        }
        if (isReconfigured) {
            synchronized (periodicKernelMetricsInProgressLock) {
                Instant lastPeriodicAggTime = Instant
                        .ofEpochMilli(MetricsAgent.getLastPeriodicAggregationMetricsTime());
                if (lastPeriodicAggTime.plusSeconds(periodicKernelMetricsIntervalSec).isBefore(Instant.now())) {
                    this.emitKernelMetrics();
                }
            }
        }
        ScheduledExecutorService ses = this.kernel.getContext().get(ScheduledExecutorService.class);
        periodicKernelMetricsFuture = ses.scheduleWithFixedDelay(
                this::emitKernelMetrics, 0, periodicKernelMetricsIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Collect kernel metrics - Number of components running in each state.
     */
    protected void collectKernelComponentState() {
        List<TelemetryMetricName> telemetryMetricNames =
                TelemetryMetricName.getMetricNamesOf(TelemetryNamespace.KernelComponents);
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            Metric metric = Metric.builder()
                    .metricNamespace(TelemetryNamespace.KernelComponents)
                    .metricName(telemetryMetricName)
                    .metricUnit(TelemetryUnit.Count)
                    .metricAggregation(TelemetryAggregation.Average)
                    .build();
            MetricDataBuilder metricDataBuilder = new MetricFactory(KERNEL_COMPONENT_METRIC_STORE).addMetric(metric);
            kernelMetrics.put(telemetryMetricName, metricDataBuilder);
        }
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            kernelMetricsData.put(telemetryMetricName, 0);
        }
        kernel.getConfig().lookup(PARAMETERS_CONFIG_KEY, TELEMETRY_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_AGGREGATE_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicKernelMetricsIntervalSec = Coerce.toInt(newv);
                    if (periodicKernelMetricsFuture != null) {
                        this.schedulePeriodicKernelMetrics(true);
                    }
                });
        this.schedulePeriodicKernelMetrics(false);
    }

    private void emitKernelMetrics() {
        Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
        for (EvergreenService evergreenService : evergreenServices) {
            String serviceState = evergreenService.getState().toString();
            serviceState = serviceState.charAt(0) + serviceState.substring(1).toLowerCase();
            try {
                TelemetryMetricName telemetryMetricName =
                        TelemetryMetricName.valueOf("NumberOfComponents" + serviceState);
                kernelMetricsData.put(telemetryMetricName, kernelMetricsData.get(telemetryMetricName) + 1);
            } catch (IllegalArgumentException e) {
                logger.atError().log("Unable to find the metric name." + e);
            }
        }
        for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> kernelMetric : kernelMetrics.entrySet()) {
            MetricDataBuilder metricDataBuilder = kernelMetric.getValue();
            metricDataBuilder.putMetricData(kernelMetricsData.get(kernelMetric.getKey())).emit();
            kernelMetricsData.put(kernelMetric.getKey(), 0);
        }
    }
}
