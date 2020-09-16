/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.telemetry;

import java.util.concurrent.ScheduledFuture;

public abstract class PeriodicMetricsEmitter {
    protected ScheduledFuture future;

    /**
     * This method will be scheduled to run. So this method typically assigns values to the metrics and emit them.
     */
    public abstract void emitMetrics();
}
