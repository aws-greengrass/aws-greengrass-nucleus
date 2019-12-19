package com.aws.iot.evergreen.util.logging.api;

public interface MetricsFactory {
    String getName();

    void addDefaultDimension(String key, Object value);

    MetricsBuilder newMetrics();
}
