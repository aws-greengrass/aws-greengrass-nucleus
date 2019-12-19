package com.aws.iot.evergreen.util.logging.api;

public interface Logger {
    String getName();

    void addDefaultKeyValue(String key, Object value);

    LogEventBuilder atTrace();
    LogEventBuilder atDebug();
    LogEventBuilder atInfo();
    LogEventBuilder atWarn();
    LogEventBuilder atError();
    LogEventBuilder atFatal();

    boolean isTraceEnabled();
    boolean isDebugEnabled();
    boolean isInfoEnabled();
    boolean isWarnEnabled();
    boolean isErrorEnabled();
    boolean isFatalEnabled();

    // For string templating
    void trace(String message, Object... args);
    void debug(String message, Object... args);
    void info(String message, Object... args);
    void warn(String message, Object... args);
    void error(String message, Object... args);
    void fatal(String message, Object... args);
}
