package com.aws.iot.evergreen.util.logging.api;

public interface LogEventBuilder {
    LogEventBuilder setCause(Throwable cause);
    LogEventBuilder setEventType(String type);
    LogEventBuilder addKeyValue(String key, Object value);

    void log();
    void log(Object arg); // serialize the object as value for key "MSG"
    // For string templating
    void log(String message, Object... args);
}
