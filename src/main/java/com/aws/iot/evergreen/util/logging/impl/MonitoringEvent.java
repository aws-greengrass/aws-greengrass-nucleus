package com.aws.iot.evergreen.util.logging.impl;

import java.io.Serializable;
import java.time.Instant;

public abstract class MonitoringEvent implements Serializable {
    public String loggerName;
    public Instant timestamp;
}
