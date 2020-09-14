package com.aws.iot.evergreen.telemetry;

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

public class SystemMetricsEmitter {
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    private static final String SYSTEM_METRICS_STORE = TelemetryNamespace.SystemMetrics.toString();
    private static final CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private static final SystemInfo systemInfo = new SystemInfo();
    private final Map<TelemetryMetricName, MetricDataBuilder> systemMetrics = new HashMap<>();
    private final MetricFactory mf = new MetricFactory(SYSTEM_METRICS_STORE);
    private long[] previousTicks = new long[CentralProcessor.TickType.values().length];

    /**
     * Create system metrics.
     */
    protected void collectSystemMetrics() {
        Metric systemMetric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.CpuUsage)
                .unit(TelemetryUnit.Percent)
                .aggregation(TelemetryAggregation.Average)
                .build();
        MetricDataBuilder mdb = mf.addMetric(systemMetric);
        systemMetrics.put(TelemetryMetricName.CpuUsage, mdb);

        systemMetric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.TotalNumberOfFDs)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mdb = mf.addMetric(systemMetric);
        systemMetrics.put(TelemetryMetricName.TotalNumberOfFDs, mdb);

        systemMetric = Metric.builder()
                .namespace(TelemetryNamespace.SystemMetrics)
                .name(TelemetryMetricName.SystemMemUsage)
                .unit(TelemetryUnit.Megabytes)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mdb = mf.addMetric(systemMetric);
        systemMetrics.put(TelemetryMetricName.SystemMemUsage, mdb);
    }

    protected void emitMetrics() {
        MetricDataBuilder mdb = systemMetrics.get(TelemetryMetricName.CpuUsage);
        mdb.putMetricData(cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER);
        previousTicks = cpu.getSystemCpuLoadTicks();

        mdb = systemMetrics.get(TelemetryMetricName.SystemMemUsage);
        mdb.putMetricData(systemInfo.getHardware().getMemory().getVirtualMemory().getVirtualInUse() / MB_CONVERTER);

        mdb = systemMetrics.get(TelemetryMetricName.TotalNumberOfFDs);
        mdb.putMetricData(systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());
    }
}
