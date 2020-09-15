package com.aws.iot.evergreen.telemetry;

import java.util.concurrent.ScheduledFuture;

public abstract class PeriodicMetricsEmitter {
    public ScheduledFuture future;
    public abstract void buildMetrics();
    public abstract void emitMetrics();
}
