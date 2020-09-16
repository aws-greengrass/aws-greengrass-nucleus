/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.dependency.State;
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
import java.util.Map;
import javax.inject.Inject;

public class KernelMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String KERNEL_COMPONENT_METRIC_STORE = TelemetryNamespace.KernelComponents.toString();
    private final Kernel kernel;
    private final MetricFactory mf = new MetricFactory(KERNEL_COMPONENT_METRIC_STORE);

    /**
     * Constructor for kernel metrics emitter.
     *
     * @param kernel {@link Kernel}
     */
    @Inject
    public KernelMetricsEmitter(Kernel kernel) {
        super();
        this.kernel = kernel;
    }

    /**
     * Emit kernel component state metrics.
     */
    @Override
    public void emitMetrics() {
        Map<State, Integer> stateCount = new HashMap<>();
        Collection<EvergreenService> evergreenServices = kernel.orderedDependencies();
        for (EvergreenService evergreenService : evergreenServices) {
            stateCount.put(evergreenService.getState(), stateCount.getOrDefault(evergreenService.getState(), 0) + 1);
        }

        Metric metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsStarting)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.STARTING, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsInstalled)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.INSTALLED, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsStateless)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.STATELESS, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsStopping)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.STOPPING, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsBroken)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.BROKEN, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsRunning)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.RUNNING, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsErrored)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.ERRORED, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsNew)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.NEW, 0));

        metric = Metric.builder()
                .namespace(TelemetryNamespace.KernelComponents)
                .name(TelemetryMetricName.NumberOfComponentsFinished)
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.FINISHED, 0));
    }
}
