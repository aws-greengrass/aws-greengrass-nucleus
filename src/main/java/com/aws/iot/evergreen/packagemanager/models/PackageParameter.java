package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.EqualsAndHashCode;
import lombok.Getter;

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
     * Create a PackageRecipe Param object.
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
     * Create a PackageRecipe Param object.
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

    public enum ParameterType {
        NUMBER("Number"), STRING("String"), BOOLEAN("Boolean");

        private String parameterType;

        ParameterType(final String val) {
            this.parameterType = val;
        }
    }
}
