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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class SystemMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(SystemMetricsEmitter.class);
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    public static final String NAMESPACE = "SystemMetrics";
#if !ANDROID
    private static final SystemInfo systemInfo = new SystemInfo();
    private static final CentralProcessor cpu = systemInfo.getHardware().getProcessor();
#endif
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

#if ANDROID
    private long getUsedMemory() {
        long usedMemory = -1;
        try {
            MemoryInfo mi = new MemoryInfo();
            Class activityThreadClass = Class.forName("software.amazon.awssdk.greengrasssamples.MainActivity");
            Field contextField = activityThreadClass.getDeclaredField("context");
            Context ctx = (Context) contextField.get(null);
            ActivityManager activityManager = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            activityManager.getMemoryInfo(mi);
            usedMemory = mi.totalMem - mi.availMem;
        } catch (Exception ex) {
            logger.atInfo().log(ex);
        }
        return usedMemory;
    }

    private long getOpenFileDescriptorsCount() {
        /* We are starting from -1. Even if command will be executed correctly, the first line of
           responce is header and should not be counted. */
        long openFDCount = -1;
        try {
            /* lsof returns list of open file descriptors for current process */
            java.lang.Process proc = Runtime.getRuntime().exec(
                    "/system/bin/lsof -p ".concat(String.valueOf(android.os.Process.myPid())));
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            while (stdInput.readLine() != null) {
                openFDCount += 1;
            }
        } catch (Exception ex) {
            logger.atInfo().log(ex);
        }
        return openFDCount;
    }

    private double getCpuLoad() {
        double cpuLoad = -1.;
        try {
            Class activityThreadClass = Class.forName("software.amazon.awssdk.greengrasssamples.MainActivity");
            Field contextField = activityThreadClass.getDeclaredField("context");
            Context ctx = (Context) contextField.get(null);
            HardwarePropertiesManager hardwarePropertiesManager = ctx.getSystemService(HardwarePropertiesManager.class);
            /* FIXME: Rework resulting data when nucleus will be system service with required permissions */
            CpuUsageInfo[] cpuUsages = hardwarePropertiesManager.getCpuUsages();
        } catch (Exception ex) {
            /* FIXME: Change atDebug with atInfo when nucleus will be system service */
            logger.atDebug().log(ex);
        }
        return cpuLoad;
    }
#endif

    /**
     * Retrieve kernel component state metrics.
     * @return a list of {@link Metric}
     */
    @Override
    public List<Metric> getMetrics() {
        List<Metric> metricsList = new ArrayList<>();
        long timestamp = Instant.now().toEpochMilli();

#if ANDROID
        double cpuLoad = getCpuLoad();
        long openFileDescriptorsCount = getOpenFileDescriptorsCount();
        long usedMemory = getUsedMemory();
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
