package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@JsonSerialize
public class PackageParameter {

    @EqualsAndHashCode.Include
    private final String name;

    private String value;

    @EqualsAndHashCode.Include
    private final ParameterType type;

    /**
     * Create a Package Param object.
     *
     * @param name  Name of the parameter
     * @param value Default value for the parameter
     * @param type  Parameter Type
     */
    @JsonCreator
    //TODO: Json property names should match with other configuration members. They start with capital first letters
    public PackageParameter(@JsonProperty("name") String name, @JsonProperty("value") String value,
                            @JsonProperty("type") String type) {
        this(name, value, ParameterType.valueOf(type.toUpperCase()));
    }

    /**
     * Create a Package Param object.
     *
     * @param name  Name of the parameter
     * @param value Default value for the parameter
     * @param type  Parameter Type enum value
     */
    public PackageParameter(String name, String value, ParameterType type) {
        this.name = name;
        this.type = type;
        // TODO: Validate type and initialize corresponding type here?
        this.value = value;
    }

    /**
     * Get a set of parameters from a map of ParameterName -> ParameterValue.
     *
     * @param configuration map of parameters
     * @return set of parameters
     */
    public static Set<PackageParameter> fromMap(Map<String, Object> configuration) {
        if (configuration == null || configuration.isEmpty()) {
            return Collections.emptySet();
        }

        Set<PackageParameter> set = new HashSet<>();
        for (Map.Entry<String, Object> parameter : configuration.entrySet()) {
            Object value = parameter.getValue();
            if (value instanceof String) {
                set.add(new PackageParameter(parameter.getKey(), (String) value, ParameterType.STRING));
            } else if (value instanceof Boolean) {
                set.add(new PackageParameter(parameter.getKey(), ((Boolean) value).toString(), ParameterType.BOOLEAN));
            } else if (value instanceof Number) {
                set.add(new PackageParameter(parameter.getKey(), String.valueOf(value), ParameterType.NUMBER));
            }
        }
        return set;
    }

    public enum ParameterType {
        NUMBER("Number"), STRING("String"), BOOLEAN("Boolean");

        private String parameterType;

        ParameterType(final String val) {
            this.parameterType = val;
        }
    }
}
