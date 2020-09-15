/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.PeriodicMetricsEmitter;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryMetricName;
import com.aws.iot.evergreen.telemetry.models.TelemetryNamespace;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;

public class KernelMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String KERNEL_COMPONENT_METRIC_STORE = TelemetryNamespace.KernelComponents.toString();
    private static Map<TelemetryMetricName, Metric> map = new HashMap<>();
    private static Map<TelemetryMetricName, Integer> data = new HashMap<>();
    private final Kernel kernel;
    private final MetricFactory mf = new MetricFactory(KERNEL_COMPONENT_METRIC_STORE);

    /**
     * Constructor for kernel metrics emitter.
     *
     * @param kernel {@link Kernel}
     */
    @Inject
    public KernelMetricsEmitter(Kernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Build kernel component metrics
     */
    @Override
    public void buildMetrics() {
        List<TelemetryMetricName> telemetryMetricNames =
                TelemetryMetricName.getMetricNamesOf(TelemetryNamespace.KernelComponents);
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            Metric metric = Metric.builder()
                    .namespace(TelemetryNamespace.KernelComponents)
                    .name(telemetryMetricName)
                    .unit(TelemetryUnit.Count)
                    .aggregation(TelemetryAggregation.Average)
                    .build();
            map.put(telemetryMetricName, metric);
        }
        for (TelemetryMetricName telemetryMetricName : telemetryMetricNames) {
            data.put(telemetryMetricName, 0);
        }
    }

    /**
     * Emit kernel component state metrics.
     */
    @Override
    public void emitMetrics() {
        Metric metric;
        Object value;
        Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
        for (EvergreenService evergreenService : evergreenServices) {
            /*
              State of the component returned is all caps("RUNNING") but the corresponding Metric name is of the
              format "NumberOfComponentsRunning"; So we process the uppercase service state to sentence case
              and concatenate it with "NumberOfComponents" to form the metric name;
             */
            String serviceState = evergreenService.getState().toString();
            serviceState = serviceState.charAt(0) + serviceState.substring(1).toLowerCase();
            try {
                TelemetryMetricName telemetryMetricName =
                        TelemetryMetricName.valueOf("NumberOfComponents" + serviceState);
                data.put(telemetryMetricName, data.get(telemetryMetricName) + 1);
            } catch (IllegalArgumentException e) {
                logger.atError().log("Unable to find the metric name.", e);
            }
        }
        for (HashMap.Entry<TelemetryMetricName, Metric> m : map.entrySet()) {
            metric = m.getValue();
            value = data.get(m.getKey());
            mf.putMetricData(metric, value);
            data.put(m.getKey(), 0);
        }
    }
}
