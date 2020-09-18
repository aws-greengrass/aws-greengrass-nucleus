/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.telemetry.PeriodicMetricsEmitter;
import com.aws.iot.evergreen.telemetry.TelemetryAgent;
import com.aws.iot.evergreen.telemetry.impl.InvalidMetricException;
import com.aws.iot.evergreen.telemetry.impl.Metric;
import com.aws.iot.evergreen.telemetry.impl.MetricFactory;
import com.aws.iot.evergreen.telemetry.models.TelemetryAggregation;
import com.aws.iot.evergreen.telemetry.models.TelemetryUnit;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;

public class KernelMetricsEmitter extends PeriodicMetricsEmitter {
    public static final Logger logger = LogManager.getLogger(KernelMetricsEmitter.class);
    private static final String NAMESPACE = "KernelComponents";
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
        TelemetryAgent.getTELEMETRY_NAMESPACES().add(NAMESPACE);
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
        try {
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
        } catch (InvalidMetricException e) {
            logger.atError().cause(e).log("The metric passed in is invalid.");
        }
    }
}
