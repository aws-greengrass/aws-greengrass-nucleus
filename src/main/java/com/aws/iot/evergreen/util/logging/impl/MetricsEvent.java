package com.aws.iot.evergreen.util.logging.impl;

import java.util.List;
import java.util.Map;

public class MetricsEvent extends MonitoringEvent {
    public String namespace;
    public List<Metric> metrics;
    public Map<String, String> dimensions;
}
