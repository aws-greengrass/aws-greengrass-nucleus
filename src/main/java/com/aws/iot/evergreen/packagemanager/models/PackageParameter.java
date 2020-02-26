package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@EqualsAndHashCode
public class PackageParameter {

    private final String name;

    @Setter
    private String value;

    private final ParameterType type;

    /**
     * Create a Package Param object.
     * @param name Name of the parameter
     * @param value Default value for the parameter
     * @param type Parameter Type
     */
    @JsonCreator
    public PackageParameter(@JsonProperty("name") String name,
                            @JsonProperty("value") String value,
                            @JsonProperty("type") String type) {
        this.name = name;
        // TODO: Quick fix to get this working, probably can be simplified
        this.type = ParameterType.valueOf(type.toUpperCase());

        // TODO: Validate type and initialize corresponding type here?
        this.value = value;
    }

    public enum ParameterType {
        NUMBER("Number"),
        STRING("String"),
        BOOLEAN("Boolean");

        private String parameterType;

        ParameterType(final String val) {
            this.parameterType = val;
        }
    }
}
