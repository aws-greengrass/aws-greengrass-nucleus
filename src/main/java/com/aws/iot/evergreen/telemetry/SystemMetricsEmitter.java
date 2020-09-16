/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    private static final String SYSTEM_METRICS_STORE = TelemetryNamespace.SystemMetrics.toString();
    private static final CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private static final SystemInfo systemInfo = new SystemInfo();
    private final MetricFactory mf = new MetricFactory(SYSTEM_METRICS_STORE);
    private long[] previousTicks = new long[CentralProcessor.TickType.values().length];

    @Override
    public void emitMetrics() {
        Metric metric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.CpuUsage)
                .unit(TelemetryUnit.Percent)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER);
        previousTicks = cpu.getSystemCpuLoadTicks();

        metric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.TotalNumberOfFDs)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, systemInfo.getHardware().getMemory().getVirtualMemory().getVirtualInUse()
                / MB_CONVERTER);

        metric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.SystemMemUsage)
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());
    }
}
