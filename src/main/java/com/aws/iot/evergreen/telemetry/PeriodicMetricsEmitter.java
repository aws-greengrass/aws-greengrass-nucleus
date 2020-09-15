package com.aws.iot.evergreen.telemetry;

import java.util.concurrent.ScheduledFuture;

public abstract class PeriodicMetricsEmitter {
    public ScheduledFuture future;

    /**
     * Create metrics with namespace,name,unit and aggregation type. The metrics created here can be used to emit the
     * values in emitMetrics() function as we don't want to create new metrics every time the scheduler is run.
     */
    public abstract void buildMetrics();

    /**
     * This method will be scheduled to run. So this method typically assigns values to the metrics and emit them.
     */
    public abstract void emitMetrics();
}
