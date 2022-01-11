/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import android.app.ActivityManager;
import android.app.ActivityManager.MemoryInfo;
import android.content.Context;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.models.TelemetryAggregation;
import com.aws.greengrass.telemetry.models.TelemetryUnit;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;

import java.lang.Class;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(SystemMetricsEmitter.class);
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    public static final String NAMESPACE = "SystemMetrics";
#if !ANDROID
    private SystemInfo systemInfo;
    private CentralProcessor cpu;
#endif
    private final MetricFactory mf = new MetricFactory(NAMESPACE);
    private long[] previousTicks = new long[CentralProcessor.TickType.values().length];

    SystemMetricsEmitter() {
#if !ANDROID
            systemInfo = new SystemInfo();
            cpu = systemInfo.getHardware().getProcessor();
#endif
    }

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

#if ANDROID
        MemoryInfo mi = new MemoryInfo();
        try {
            Class activityThreadClass = Class.forName("software.amazon.awssdk.greengrasssamplesx.MainActivity");
            Field contextField = activityThreadClass.getDeclaredField("context");
            Context ctx = (Context) contextField.get(null);
            ActivityManager activityManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(mi);
        } catch (ClassNotFoundException | IllegalAccessException | NoSuchFieldException e) {
            e.printStackTrace();
            return metricsList;
        }

        long usedMemory = mi.availMem;
        logger.atInfo().log(usedMemory);
        long openFileDescriptorsCount = 0;
        double cpuLoad = .0;
#else
        double cpuLoad = cpu.getSystemCpuLoadBetweenTicks(previousTicks);
        previousTicks = cpu.getSystemCpuLoadTicks();

        long openFileDescriptorsCount = systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors();
        GlobalMemory memory = systemInfo.getHardware().getMemory();
        long usedMemory = memory.getTotal() - memory.getAvailable();
#endif
        Metric metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("CpuUsage")
                .unit(TelemetryUnit.Percent)
                .aggregation(TelemetryAggregation.Average)
                .value(cpuLoad * PERCENTAGE_CONVERTER)
                .timestamp(timestamp)
                .build();
        metricsList.add(metric);

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("TotalNumberOfFDs")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .value(openFileDescriptorsCount)
                .timestamp(timestamp)
                .build();
        metricsList.add(metric);

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("SystemMemUsage")
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .value(usedMemory / MB_CONVERTER)
                .timestamp(timestamp)
                .build();
        metricsList.add(metric);

        return metricsList;
    }
}
