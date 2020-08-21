

/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.telemetry;


import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;

import javax.inject.Inject;

@ImplementsService(name = MetricsAgent.METRICS_AGENT_SERVICE_TOPICS, version = "1.0.0", autostart = true)
public class MetricsAgent extends EvergreenService {
    public static final String METRICS_AGENT_SERVICE_TOPICS = "MetricsAgent";
    private final MetricsAgentFactory metricsAgentFactory = new MetricsAgentFactory();
    @Inject
    private final Kernel kernel;

    @Inject
    public MetricsAgent(Topics topics, Kernel kernel) {
        super(topics);
        this.kernel = kernel;
    }

    @Override
    protected void startup() {
        logger.atInfo().log("Starting MetricsAgent.");
        reportState(State.RUNNING);
        metricsAgentFactory.collectTimeBasedMetrics(this.kernel);
    }

    @Override
    protected void shutdown() {
        logger.atInfo().log("MetricsAgent is shutting down!");
    }
}
