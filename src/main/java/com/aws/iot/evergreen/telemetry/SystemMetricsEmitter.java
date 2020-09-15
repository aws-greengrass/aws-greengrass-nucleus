/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

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

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    private static final String SYSTEM_METRICS_STORE = TelemetryNamespace.SystemMetrics.toString();
    private static final CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private static final SystemInfo systemInfo = new SystemInfo();
    private final Map<TelemetryMetricName, Metric> map = new HashMap<>();
    private final MetricFactory mf = new MetricFactory(SYSTEM_METRICS_STORE);
    private long[] previousTicks = new long[CentralProcessor.TickType.values().length];

    /**
     * Build system metrics.
     */
    @Override
    public void buildMetrics() {
        Metric systemMetric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.CpuUsage)
                .unit(TelemetryUnit.Percent)
                .aggregation(TelemetryAggregation.Average)
                .build();
        map.put(TelemetryMetricName.CpuUsage, systemMetric);

        systemMetric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.TotalNumberOfFDs)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();

        map.put(TelemetryMetricName.TotalNumberOfFDs, systemMetric);

        systemMetric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.SystemMemUsage)
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .build();
        map.put(TelemetryMetricName.SystemMemUsage, systemMetric);
    }

    @Override
    public void emitMetrics() {
        Metric m = map.get(TelemetryMetricName.CpuUsage);
        mf.putMetricData(m,cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER);
        previousTicks = cpu.getSystemCpuLoadTicks();

        m = map.get(TelemetryMetricName.SystemMemUsage);
        mf.putMetricData(m,systemInfo.getHardware().getMemory().getVirtualMemory().getVirtualInUse() / MB_CONVERTER);

        m = map.get(TelemetryMetricName.TotalNumberOfFDs);
        mf.putMetricData(m,systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());
    }

}
