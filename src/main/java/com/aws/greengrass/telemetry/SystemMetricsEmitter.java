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
import oshi.hardware.GlobalMemory;

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(SystemMetricsEmitter.class);
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    public static final String NAMESPACE = "SystemMetrics";
    private static final SystemInfo systemInfo = new SystemInfo();
    private static final CentralProcessor cpu = systemInfo.getHardware().getProcessor();
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
        mf.putMetricData(metric, systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("SystemMemUsage")
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .build();
        GlobalMemory memory = systemInfo.getHardware().getMemory();
        mf.putMetricData(metric, (memory.getTotal() - memory.getAvailable()) / MB_CONVERTER);
    }
}
