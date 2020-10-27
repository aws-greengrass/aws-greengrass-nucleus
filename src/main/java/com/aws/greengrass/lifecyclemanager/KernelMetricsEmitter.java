/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.dependency.State;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.PeriodicMetricsEmitter;
import com.aws.greengrass.telemetry.impl.Metric;
import com.aws.greengrass.telemetry.impl.MetricFactory;
import com.aws.greengrass.telemetry.models.TelemetryAggregation;
import com.aws.greengrass.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;
import javax.inject.Inject;

public class KernelMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String NAMESPACE = "GreengrassComponents";
    private final Kernel kernel;
    private final MetricFactory mf = new MetricFactory(NAMESPACE);

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
        Map<State, Integer> stateCount = new EnumMap<>(State.class);
        Collection<GreengrassService> services = kernel.orderedDependencies();
        for (GreengrassService service : services) {
            stateCount.put(service.getState(), stateCount.getOrDefault(service.getState(), 0) + 1);
        }
        Metric metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsStarting")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.STARTING, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsInstalled")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.INSTALLED, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsStateless")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.STATELESS, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsStopping")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.STOPPING, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsBroken")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.BROKEN, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsRunning")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.RUNNING, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsErrored")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.ERRORED, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsNew")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.NEW, 0));

        metric = Metric.builder()
                .namespace(NAMESPACE)
                .name("NumberOfComponentsFinished")
                .unit(TelemetryUnit.Count)
                .aggregation(TelemetryAggregation.Average)
                .build();
        mf.putMetricData(metric, stateCount.getOrDefault(State.FINISHED, 0));
    }
}
