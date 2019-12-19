package com.aws.iot.evergreen.util.logging.impl;

import java.util.Map;

public class LogEvent {
    public Level level;
    public String message;
    public Throwable throwable;
    public Map<String, Object> contexts;
}
