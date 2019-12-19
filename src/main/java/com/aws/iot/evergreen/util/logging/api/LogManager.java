package com.aws.iot.evergreen.util.logging.api;

public interface LogManager {
    Logger getLogger(String name);
    Logger getLogger(Class<?> clazz);

    MetricsFactory getMetricsFactory(String name);
    MetricsFactory getMetricsFactory(Class<?> clazz);
}
