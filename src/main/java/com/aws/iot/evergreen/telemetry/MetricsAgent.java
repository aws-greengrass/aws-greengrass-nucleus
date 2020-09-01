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

    public MetricsAgent(Topics topics) {
        super(topics);
    }

    @Override
    public void startup() {
        reportState(State.RUNNING);
        this.systemMetricsEmitter.collectSystemMetrics(getContext());
        this.metricsAggregator.aggregateMetrics(getContext());
        this.metricsUploader.uploadMetrics(getContext());
    }

    @Override
    public void shutdown() {
    }

     /**
     * This will be removed when we read the data from config file.
     * @return Map with namespace as a key and metric data config as a value.
     */
     public static Map<String, TelemetryDataConfig> createSampleConfiguration() {
        TelemetryDataConfig kerConfig = new TelemetryDataConfig("KernelComponents",10_000,30_000,60_000,"Average");
        TelemetryDataConfig sysMetConfig = new TelemetryDataConfig("SystemMetrics",10_000,30_000,60_000,"Average");
        Map<String, TelemetryDataConfig> configMap = new HashMap<>();
        configMap.put(kerConfig.getMetricNamespace(),kerConfig);
        configMap.put(sysMetConfig.getMetricNamespace(),sysMetConfig);
        return configMap;
    }
}
