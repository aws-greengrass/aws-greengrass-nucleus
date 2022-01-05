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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(SystemMetricsEmitter.class);
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    public static final String NAMESPACE = "SystemMetrics";
    private static final SystemInfo systemInfo = new SystemInfo();
    private static final CentralProcessor cpu = systemInfo.getHardware().getProcessor();
    private final MetricFactory mf = new MetricFactory(NAMESPACE);
    private long[] previousTicks = new long[CentralProcessor.TickType.values().length];

    /**
     * Emit kernel component state metrics.
     */
    @Override
    public void emitMetrics() {
        List<Metric> retrievedMetrics = getMetrics();
        for (Metric retrievedMetric : retrievedMetrics) {
            mf.putMetricData(retrievedMetric);
        }
    }

    /**
     * Retrieve kernel component state metrics.
     * @return a list of {@link Metric}
     */
    @Override
    public List<Metric> getMetrics() {
        List<Metric> metricsList = new ArrayList<>();
        long timestamp = Instant.now().toEpochMilli();

        Metric metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("CpuUsage")
                .unit(TelemetryUnit.Percent)
                .aggregation(TelemetryAggregation.Average)
                .value(cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER)
                .timestamp(timestamp)
                .build();
        previousTicks = cpu.getSystemCpuLoadTicks();
        metricsList.add(metric);

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("TotalNumberOfFDs")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .value(systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors())
                .timestamp(timestamp)
                .build();
        metricsList.add(metric);

        GlobalMemory memory = systemInfo.getHardware().getMemory();
        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("SystemMemUsage")
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .value((memory.getTotal() - memory.getAvailable()) / MB_CONVERTER)
                .timestamp(timestamp)
                .build();
        metricsList.add(metric);

        return metricsList;
    }
}
