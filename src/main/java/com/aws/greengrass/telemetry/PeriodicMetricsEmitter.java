/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.telemetry;

import com.aws.greengrass.telemetry.impl.Metric;

import java.util.List;
import java.util.concurrent.ScheduledFuture;

public abstract class PeriodicMetricsEmitter {
    protected ScheduledFuture<?> future;

    /**
     * This method will be scheduled to run. So this method typically assigns values to the metrics and emit them.
     * Uses getMetrics() to get the raw metric data.
     */
    public abstract void emitMetrics();

    /**
     * This method can be called on demand. So this method typically returns raw metric data.
     */
    public abstract List<Metric> getMetrics();
}
