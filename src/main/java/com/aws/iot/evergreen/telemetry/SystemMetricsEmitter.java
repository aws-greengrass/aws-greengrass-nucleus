package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.telemetry.MetricsAgent.createSampleConfiguration;

public class SystemMetricsEmitter {
    private static final int MB_CONVERTER = 1024 * 1024;
    private static final int PERCENTAGE_CONVERTER = 100;
    private static long SYSTEM_METRICS_PERIOD = createSampleConfiguration()
            .get(TelemetryNamespace.SystemMetrics.toString()).getEmitFrequency();
    private static String SYSTEM_METRICS_STORE = TelemetryNamespace.SystemMetrics.toString();
    private static CentralProcessor cpu = new SystemInfo().getHardware().getProcessor();
    private static SystemInfo systemInfo = new SystemInfo();
    private static long[] previousTicks = new long[CentralProcessor.TickType.values().length];
    private static Map<TelemetryMetricName, Object> systemMetricsData = new HashMap<>();
    private static Map<TelemetryMetricName, MetricDataBuilder> systemMetrics = new HashMap<>();

    /**
     * Create system metrics, collect and emit periodically.
     *
     */
    protected void collectSystemMetrics(Context context) {
        Metric systemMetric = Metric.builder()
                .metricNamespace(TelemetryNamespace.SystemMetrics)
                .metricName(TelemetryMetricName.CpuUsage)
                .metricUnit(TelemetryUnit.Percent)
                .build();
        MetricDataBuilder mdb = new MetricFactory(SYSTEM_METRICS_STORE).addMetric(systemMetric);
        systemMetrics.put(TelemetryMetricName.CpuUsage, mdb);

        systemMetric = Metric.builder()
                .metricNamespace(TelemetryNamespace.SystemMetrics)
                .metricName(TelemetryMetricName.TotalNumberOfFDs)
                .metricUnit(TelemetryUnit.Count)
                .build();
        mdb = new MetricFactory(SYSTEM_METRICS_STORE).addMetric(systemMetric);
        systemMetrics.put(TelemetryMetricName.TotalNumberOfFDs, mdb);

        systemMetric = Metric.builder()
                .metricNamespace(TelemetryNamespace.SystemMetrics)
                .metricName(TelemetryMetricName.SystemMemUsage)
                .metricUnit(TelemetryUnit.Megabytes)
                .build();
        mdb = new MetricFactory(SYSTEM_METRICS_STORE).addMetric(systemMetric);
        systemMetrics.put(TelemetryMetricName.SystemMemUsage, mdb);

        for (TelemetryMetricName telemetryMetricName : TelemetryMetricName.values()) {
            systemMetricsData.put(telemetryMetricName, 0);
        }

        ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
        executor.scheduleAtFixedRate(emitMetrics(), 0, SYSTEM_METRICS_PERIOD, TimeUnit.MILLISECONDS);
    }

    private Runnable emitMetrics() {
        return () -> {
            systemMetricsData.put(TelemetryMetricName.CpuUsage,
                    cpu.getSystemCpuLoadBetweenTicks(previousTicks) * PERCENTAGE_CONVERTER);
            previousTicks = cpu.getSystemCpuLoadTicks();

            systemMetricsData.put(TelemetryMetricName.TotalNumberOfFDs,
                    systemInfo.getOperatingSystem().getFileSystem().getOpenFileDescriptors());

            systemMetricsData.put(TelemetryMetricName.SystemMemUsage,
                    systemInfo.getHardware().getMemory().getVirtualMemory().getVirtualInUse() / MB_CONVERTER);

            for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> systemMetric : systemMetrics.entrySet()) {
                MetricDataBuilder metricDataBuilder = systemMetric.getValue();
                metricDataBuilder.putMetricData(systemMetricsData.get(systemMetric.getKey())).emit();
                systemMetricsData.put(systemMetric.getKey(),0);
            }
        };
    }
}
