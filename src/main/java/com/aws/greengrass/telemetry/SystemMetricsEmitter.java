/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.models.TelemetryAggregation;
import com.aws.greengrass.telemetry.models.TelemetryUnit;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(SystemMetricsEmitter.class);
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    private static final String NAMESPACE = "SystemMetrics";
    private static final CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private static final SystemInfo systemInfo = new SystemInfo();
    private final MetricFactory mf = new MetricFactory(NAMESPACE);
    private long[] previousTicks = new long[CentralProcessor.TickType.values().length];

    @Override
    public void emitMetrics() {
        Metric metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("CpuUsage")
                .unit(TelemetryUnit.Percent)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER);
        previousTicks = cpu.getSystemCpuLoadTicks();

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("TotalNumberOfFDs")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, systemInfo.getHardware().getMemory().getVirtualMemory().getVirtualInUse()
                / MB_CONVERTER);

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("SystemMemUsage")
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());
    }
}
