/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.telemetry.config.TelemetryDataConfig;

import java.util.HashMap;
import java.util.Map;

@ImplementsService(name = MetricsAgent.METRICS_AGENT_SERVICE_TOPICS, version = "1.0.0", autostart = true)
public class MetricsAgent extends EvergreenService {
    public static final String METRICS_AGENT_SERVICE_TOPICS = "MetricsAgent";
    private final SystemMetricsEmitter systemMetricsEmitter = new SystemMetricsEmitter();
    private final MetricsAggregator metricsAggregator = new MetricsAggregator();
    private final MetricsUploader metricsUploader = new MetricsUploader();
    public static final Map<String, TelemetryDataConfig> telemetryDataConfigMap = createSampleConfiguration();

    public MetricsAgent(Topics topics) {
        super(topics);
    }

    @Override
    public void startup() {
        // Is it always going to be true that STARTING precedes RUNNING?
        if (this.getState().equals(State.STARTING)) {
            reportState(State.RUNNING);
            this.systemMetricsEmitter.collectSystemMetrics(getContext());
            this.metricsAggregator.aggregateMetrics(getContext());
            this.metricsUploader.uploadMetrics(getContext());
        } else {
            reportState(this.getState());
        }
    }

    @Override
    public void shutdown() {
    }

     /**
     * This will be removed when we read the data from config file.
     * @return Map with namespace as a key and metric data config as a value.
     */
     private static Map<String, TelemetryDataConfig> createSampleConfiguration() {
        TelemetryDataConfig kernelConfig = new TelemetryDataConfig("KernelComponents",10_000,30_000,60_000,"Average");
        TelemetryDataConfig systemMetricsConfig = new TelemetryDataConfig("SystemMetrics",2_000,5_000,60_000,"Average");
        Map<String, TelemetryDataConfig> configMap = new HashMap<>();
        configMap.put(kernelConfig.getMetricNamespace(),kernelConfig);
        configMap.put(systemMetricsConfig.getMetricNamespace(),systemMetricsConfig);
        return configMap;
    }
}
