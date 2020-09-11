/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

public class KernelMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String KERNEL_COMPONENT_METRIC_STORE = TelemetryNamespace.KernelComponents.toString();
    private static Map<TelemetryMetricName, MetricDataBuilder> kernelMetrics = new HashMap<>();
    private static Map<TelemetryMetricName, Integer> kernelMetricsData = new HashMap<>();
    private final Kernel kernel;

    /**
     * Constructor for kernel metrics emitter.
     *
     * @param kernel {@link Kernel}
     */
    @Inject
    public KernelMetricsEmitter(Kernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Collect kernel metrics - Number of components running in each state.
     */
    public void collectKernelComponentState() {
        List<TelemetryMetricName> telemetryMetricNames =
                TelemetryMetricName.getMetricNamesOf(TelemetryNamespace.KernelComponents);
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            Metric metric = Metric.builder()
                    .namespace(TelemetryNamespace.KernelComponents)
                    .name(telemetryMetricName)
                    .unit(TelemetryUnit.Count)
                    .aggregation(TelemetryAggregation.Average)
                    .build();
            MetricDataBuilder metricDataBuilder = new MetricFactory(KERNEL_COMPONENT_METRIC_STORE).addMetric(metric);
            kernelMetrics.put(telemetryMetricName, metricDataBuilder);
        }
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            kernelMetricsData.put(telemetryMetricName, 0);
        }
    }

    /**
     * Emit kernel component state metrics.
     */
    public void emitMetrics() {
        Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
        for (EvergreenService evergreenService : evergreenServices) {
            String serviceState = evergreenService.getState().toString();
            serviceState = serviceState.charAt(0) + serviceState.substring(1).toLowerCase();
            try {
                TelemetryMetricName telemetryMetricName =
                        TelemetryMetricName.valueOf("NumberOfComponents" + serviceState);
                kernelMetricsData.put(telemetryMetricName, kernelMetricsData.get(telemetryMetricName) + 1);
            } catch (IllegalArgumentException e) {
                logger.atError().log("Unable to find the metric name.", e);
            }
        }
        for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> kernelMetric : kernelMetrics.entrySet()) {
            MetricDataBuilder metricDataBuilder = kernelMetric.getValue();
            metricDataBuilder.putMetricData(kernelMetricsData.get(kernelMetric.getKey())).emit();
            kernelMetricsData.put(kernelMetric.getKey(), 0);
        }
    }
}
