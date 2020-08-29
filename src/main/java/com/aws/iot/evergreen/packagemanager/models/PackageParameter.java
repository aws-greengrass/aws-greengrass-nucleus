package com.aws.iot.evergreen.packagemanager.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.ToString;
import lombok.Value;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Value
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@AllArgsConstructor
@ToString
public class PackageParameter {

    @NonNull
    @EqualsAndHashCode.Include // TODO Hacky way for KernelConfigResolver. Should be removed when removing `type`.
    String name;

    String value;

    @EqualsAndHashCode.Include
    ParameterType type;

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
