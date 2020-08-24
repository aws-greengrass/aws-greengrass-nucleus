/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public  class MetricsAgentFactory {
    private static final int DEFAULT_KERNEL_COMPONENTS_STATE_PERIOD = 300;

    public void collectTimeBasedMetrics(Kernel kernel, Context context) {
        this.collectKernelComponentState(kernel,context);
    }

    private void collectKernelComponentState(Kernel kernel, Context context) {
        Map<TelemetryMetricName, MetricDataBuilder> metricsMap = new HashMap<>();
        for (TelemetryMetricName telemetryMetricName : TelemetryMetricName.KernelComponents.values()) {
            Metric metric = Metric.builder()
                    .metricNamespace(TelemetryNamespace.Kernel)
                    .metricName(telemetryMetricName)
                    .metricUnit(TelemetryUnit.Count)
                    .metricAggregation(TelemetryAggregation.Sum)
                    .build();
            MetricDataBuilder metricDataBuilder = new MetricFactory().addMetric(metric);
            metricsMap.put(telemetryMetricName, metricDataBuilder);
        }
        Map<TelemetryMetricName, Integer> numComponentState = new HashMap<>();
        for (TelemetryMetricName telemetryMetricName : TelemetryMetricName.KernelComponents.values()) {
            numComponentState.put(telemetryMetricName, 0);
        }

        ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
        executor.scheduleAtFixedRate(emitMetrics(metricsMap, numComponentState, kernel), 0,
                DEFAULT_KERNEL_COMPONENTS_STATE_PERIOD, TimeUnit.SECONDS);

    }

    private Runnable emitMetrics(Map<TelemetryMetricName, MetricDataBuilder> metricsMap,
                                 Map<TelemetryMetricName, Integer> numComponentState, Kernel kernel) {
        return () -> {
            Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
            for (EvergreenService evergreenService : evergreenServices) {
                String serviceState = evergreenService.getState().toString();
                serviceState = serviceState.charAt(0) + serviceState.substring(1).toLowerCase();
                TelemetryMetricName telemetryMetricName = TelemetryMetricName.KernelComponents
                        .valueOf("NumberOfComponents" + serviceState);
                numComponentState.put(telemetryMetricName, numComponentState.get(telemetryMetricName) + 1);
            }
            for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> metricMap : metricsMap.entrySet()) {
                MetricDataBuilder metricDataBuilder = metricMap.getValue();
                metricDataBuilder.putMetricData(numComponentState.get(metricMap.getKey())).emit();
                numComponentState.put(metricMap.getKey(),0);
            }
        };
    }
}
