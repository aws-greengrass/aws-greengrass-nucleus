package com.aws.iot.evergreen.deployment.model;

public class Parameter {

    private final String name;
    private final String value;
    private final ParameterType type;

    public Parameter(String name, String value, ParameterType type) {
        this.name = name;
        this.value = value;
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public ParameterType getType() {
        return type;
    }

    public enum ParameterType {
        NUMBER, STRING, BOOLEAN
    }
}
