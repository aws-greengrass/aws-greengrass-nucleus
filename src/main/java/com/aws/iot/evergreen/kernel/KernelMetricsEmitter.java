package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.MetricsAgent;
import com.aws.iot.evergreen.telemetry.api.MetricDataBuilder;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class KernelMetricsEmitter {
    private static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);

    private static final long KERNEL_COMPONENTS_STATE_PERIOD = MetricsAgent.createSampleConfiguration()
            .get(TelemetryNamespace.KernelComponents.toString()).getEmitFrequency();
    private static final String KERNEL_COMPONENT_METRIC_STORE = TelemetryNamespace.KernelComponents.toString();
    private static Map<TelemetryMetricName, MetricDataBuilder> kernelMetrics = new HashMap<>();

    /**
     * Collect kernel metrics - Number of components running in each state.
     */
    protected void collectKernelComponentState(Context context, Kernel kernel) {
        List<TelemetryMetricName> telemetryMetricNames =
                TelemetryMetricName.getMetricNamesOf(TelemetryNamespace.KernelComponents);
        Map<TelemetryMetricName, Integer> kernelMetricsData = new HashMap<>();
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            Metric metric = Metric.builder()
                    .metricNamespace(TelemetryNamespace.KernelComponents)
                    .metricName(telemetryMetricName)
                    .metricUnit(TelemetryUnit.Count)
                    .build();
            MetricDataBuilder metricDataBuilder = new MetricFactory(KERNEL_COMPONENT_METRIC_STORE).addMetric(metric);
            kernelMetrics.put(telemetryMetricName, metricDataBuilder);
        }
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            kernelMetricsData.put(telemetryMetricName, 0);
        }
        ScheduledExecutorService executor = context.get(ScheduledExecutorService.class);
        executor.scheduleAtFixedRate(emitMetrics(kernel, kernelMetricsData),
                0, KERNEL_COMPONENTS_STATE_PERIOD, TimeUnit.MILLISECONDS);
    }

    private Runnable emitMetrics(Kernel kernel, Map<TelemetryMetricName, Integer> kernelMetricsData) {
        return () -> {
            Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
            for (EvergreenService evergreenService : evergreenServices) {
                String serviceState = evergreenService.getState().toString();
                serviceState = serviceState.charAt(0) + serviceState.substring(1).toLowerCase();
                try{
                    TelemetryMetricName telemetryMetricName =
                            TelemetryMetricName.valueOf("NumberOfComponents" + serviceState);
                    kernelMetricsData.put(telemetryMetricName, kernelMetricsData.get(telemetryMetricName) + 1);
                } catch (IllegalArgumentException e) {
                    logger.atError().log("Unable to find the metric name." + e);
                }
            }
            for (HashMap.Entry<TelemetryMetricName, MetricDataBuilder> kernelMetric : kernelMetrics.entrySet()) {
                MetricDataBuilder metricDataBuilder = kernelMetric.getValue();
                metricDataBuilder.putMetricData(kernelMetricsData.get(kernelMetric.getKey())).emit();
                kernelMetricsData.put(kernelMetric.getKey(),0);
            }

        };
    }
}
