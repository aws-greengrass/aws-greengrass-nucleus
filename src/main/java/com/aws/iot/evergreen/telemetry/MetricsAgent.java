/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.telemetry;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.EvergreenService;

import javax.inject.Inject;

@ImplementsService(name = MetricsAgent.METRICS_AGENT_SERVICE_TOPICS, version = "1.0.0", autostart = true)
public class MetricsAgent extends EvergreenService {
    public static final String METRICS_AGENT_SERVICE_TOPICS = "MetricsAgent";
    private final MetricsAgentFactory metricsAgentFactory = new MetricsAgentFactory();

    @Inject
    private Context context;

    public MetricsAgent(Topics topics) {
        super(topics);
    }

    @Override
    protected void startup() {
        reportState(State.RUNNING);
        this.metricsAgentFactory.collectSystemMetrics(this.context);
    }

    @Override
    protected void shutdown() {
    }
}
