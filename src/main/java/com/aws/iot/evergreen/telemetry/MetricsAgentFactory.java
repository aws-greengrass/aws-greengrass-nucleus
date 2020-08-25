/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public  class MetricsAgentFactory {
    private static final int DEFAULT_SYSTEM_METRICS_PERIOD = 300;
    private static int MB_CONVERTER = 1024 * 1024;
    private static int PERCENTAGE_CONVERTER = 100;
    private static CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private static SystemInfo systemInfo = new SystemInfo();
    private static long[] previousTicks = new long[CentralProcessor.TickType.values().length];


    /**
     * Kuch bhi.
     * @param context used in executor
     */
    public void collectSystemMetrics(Context context) {
        Map<TelemetryMetricName, MetricDataBuilder> metricsMap = new HashMap<>();
        Metric systemMetric = Metric.builder()
                .metricNamespace(TelemetryNamespace.SystemMetrics)
                .metricName(TelemetryMetricName.SystemMetrics.CpuUsage)
                .metricUnit(TelemetryUnit.Percent)
                .metricAggregation(TelemetryAggregation.Average)
                .build();
        MetricDataBuilder mdb = new MetricFactory().addMetric(systemMetric);
        metricsMap.put(TelemetryMetricName.SystemMetrics.CpuUsage, mdb);

        systemMetric = Metric.builder()
                .metricNamespace(TelemetryNamespace.SystemMetrics)
                .metricName(TelemetryMetricName.SystemMetrics.TotalNumberOfFDs)
                .metricUnit(TelemetryUnit.Count)
                .metricAggregation(TelemetryAggregation.Average)
                .build();
        mdb = new MetricFactory().addMetric(systemMetric);
        metricsMap.put(TelemetryMetricName.SystemMetrics.TotalNumberOfFDs, mdb);

        systemMetric = Metric.builder()
                .metricNamespace(TelemetryNamespace.SystemMetrics)
                .metricName(TelemetryMetricName.SystemMetrics.SystemMemUsage)
                .metricUnit(TelemetryUnit.Megabytes)
                .metricAggregation(TelemetryAggregation.Average)
                .build();
        mdb = new MetricFactory().addMetric(systemMetric);
        metricsMap.put(TelemetryMetricName.SystemMetrics.SystemMemUsage, mdb);

        Map<TelemetryMetricName, Object> systemMetrics = new HashMap<>();
        for (TelemetryMetricName telemetryMetricName : TelemetryMetricName.KernelComponents.values()) {
            systemMetrics.put(telemetryMetricName, 0);
        }

        ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
        executor.scheduleAtFixedRate(emitMetrics(metricsMap, systemMetrics), 0,
                DEFAULT_SYSTEM_METRICS_PERIOD, TimeUnit.SECONDS);
    }

    private Runnable emitMetrics(Map<TelemetryMetricName, MetricDataBuilder> metricsMap,
                                 Map<TelemetryMetricName, Object> systemMetrics) {
        return () -> {
            systemMetrics.put(TelemetryMetricName.SystemMetrics.CpuUsage,
                    cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER);
            previousTicks = cpu.getSystemCpuLoadTicks();
            systemMetrics.put(TelemetryMetricName.SystemMetrics.TotalNumberOfFDs,
                    systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());
            systemMetrics.put(TelemetryMetricName.SystemMetrics.SystemMemUsage,
                    systemInfo.getHardware().getMemory().getVirtualMemory().getVirtualInUse() / MB_CONVERTER);
            for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> metricMap : metricsMap.entrySet()) {
                MetricDataBuilder metricDataBuilder = metricMap.getValue();
                metricDataBuilder.putMetricData(systemMetrics.get(metricMap.getKey())).emit();
                systemMetrics.put(metricMap.getKey(),0);
            }
        };
    }
}
