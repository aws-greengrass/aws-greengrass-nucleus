package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.constants.DefaultMetricPeriod;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryType;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MetricsAgentFactory {

    public void collectTimeBasedMetrics(Kernel kernel) {
        this.collectKernelComponentState(kernel, DefaultMetricPeriod.DEFAULT_NUM_COMPONENT_STATE_PERIOD);
    }

    private void collectKernelComponentState(Kernel kernel, int period) {
        Map<TelemetryMetricName, MetricDataBuilder> metricsMap = new HashMap<>();
        for (TelemetryMetricName telemetryMetricName : TelemetryMetricName.KernelComponents.values()) {
            Metric metric = Metric.builder()
                    .metricNamespace(TelemetryNamespace.KERNEL)
                    .metricName(telemetryMetricName)
                    .metricUnit(TelemetryUnit.COUNT)
                    .metricType(TelemetryType.TIME_BASED)
                    .build();
            MetricDataBuilder metricDataBuilder = new MetricFactory().addMetric(metric);
            metricsMap.put(telemetryMetricName, metricDataBuilder);
        }

        Map<TelemetryMetricName, Integer> numComponentState = new HashMap<>();
        for (TelemetryMetricName telemetryMetricName : TelemetryMetricName.KernelComponents.values()) {
                numComponentState.put(telemetryMetricName,0);
        }

        Runnable emitMetrics = new Runnable() {
            @Override
            public void run() {
                Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
                for (EvergreenService evergreenService : evergreenServices) {
                    TelemetryMetricName telemetryMetricName = TelemetryMetricName.KernelComponents
                            .valueOf("NUM_COMPONENTS_" + evergreenService.getState().toString());
                    numComponentState.put(telemetryMetricName, numComponentState.get(telemetryMetricName) + 1);
                }

                for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> metricMap:metricsMap.entrySet()) {
                    MetricDataBuilder metricDataBuilder = metricMap.getValue();
                    metricDataBuilder.putMetricData(numComponentState.get(metricMap.getKey())).emit();
                    numComponentState.put(metricMap.getKey(),0);
                }
            }
        };

        ScheduledExecutorService executor = Executors.newScheduledThreadPool(metricsMap.size());
        executor.scheduleAtFixedRate(emitMetrics, 0, period, TimeUnit.SECONDS);
    }
}
