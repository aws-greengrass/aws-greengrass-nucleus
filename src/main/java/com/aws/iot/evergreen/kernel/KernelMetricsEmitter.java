/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.PeriodicMetricsEmitter;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;

public class KernelMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String KERNEL_COMPONENT_METRIC_STORE = TelemetryNamespace.KernelComponents.toString();
    private final Kernel kernel;
    private final MetricFactory mf = new MetricFactory(KERNEL_COMPONENT_METRIC_STORE);

    /**
     * Constructor for kernel metrics emitter.
     *
     * @param kernel {@link Kernel}
     */
    @Inject
    public KernelMetricsEmitter(Kernel kernel) {
        super();
        this.kernel = kernel;
    }

    /**
     * Emit kernel component state metrics.
     */
    @Override
    public void emitMetrics() {
        Map<TelemetryMetricName, Integer> data = new HashMap<>();
        Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
        for (EvergreenService evergreenService : evergreenServices) {
            /*
              State of the component returned is all caps("RUNNING") but the corresponding Metric name is of the
              format "NumberOfComponentsRunning"; So we process the uppercase service state to sentence case
              and concatenate it with "NumberOfComponents" to form the metric name;
             */
            String serviceState = evergreenService.getState().toString();
            serviceState = serviceState.charAt(0) + serviceState.substring(1).toLowerCase();
            try {
                TelemetryMetricName telemetryMetricName =
                        TelemetryMetricName.valueOf("NumberOfComponents" + serviceState);
                data.put(telemetryMetricName, data.getOrDefault(telemetryMetricName, 0) + 1);
            } catch (IllegalArgumentException e) {
                logger.atError().log("Unable to find the metric name.", e);
            }
        }

        Metric metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsStarting)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsStarting, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsInstalled)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsInstalled, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsStateless)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsStateless, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsStopping)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsStopping, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsBroken)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsBroken, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsRunning)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsRunning, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsErrored)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsErrored, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsNew)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsNew, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsFinished)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, data.getOrDefault(TelemetryMetricName.NumberOfComponentsFinished, 0));
    }
}
