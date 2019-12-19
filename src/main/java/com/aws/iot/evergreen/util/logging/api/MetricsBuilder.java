package com.aws.iot.evergreen.util.logging.api;

import javax.measure.unit.Unit;

public interface MetricsBuilder {
    MetricsBuilder setNamespace(String namespace);
    MetricsBuilder addDimension(String key, Object value);

    MetricsBuilder addMetric(String name, Object value, Unit<?> unit);

    void flush();
}
