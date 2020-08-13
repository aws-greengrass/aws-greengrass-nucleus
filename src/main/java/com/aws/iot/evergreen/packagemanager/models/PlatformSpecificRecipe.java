/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */
package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Data
class PlatformSpecificRecipe {
    private static final String DEPENDENCY_VERSION_REQUIREMENTS_KEY = "versionrequirements";
    private static final String DEPENDENCY_TYPE_KEY = "dependencytype";

    @JsonProperty("Platform")
    private Platform platform;

    @JsonProperty("Parameters")
    private Set<PackageParameter> packageParameters;

    @JsonProperty("Lifecycle")
    @JsonDeserialize(
            using = PackageRecipe.MapFieldDeserializer.class)
    private Map<String, Object> lifecycle;

    @JsonProperty("Artifacts")
    @JsonDeserialize(
            using = PackageRecipe.MapFieldDeserializer.class)
    private Map<String, List<ComponentArtifact>> artifacts;

    @JsonProperty("Dependencies")
    @JsonDeserialize(using = DependencyMapDeserializer.class)
    private Map<String, RecipeDependencyProperties> dependencies;


    private static class DependencyMapDeserializer extends JsonDeserializer<Map<String, RecipeDependencyProperties>> {
        @Override
        public Map<String, RecipeDependencyProperties> deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            Object resolved = PlatformResolver.resolvePlatform(
                    SerializerFactory.getRecipeSerializer().convertValue(p.readValueAsTree(), Map.class));
            if (resolved == null) {
                return Collections.emptyMap();
            }
            if (!(resolved instanceof Map)) {
                throw new IOException(String.format("Illegal dependency syntax in package recipe. Dependencies "
                        + "after platform resolution should be a map, but actually: %s", resolved));
            }

            Map<String, RecipeDependencyProperties> dependencyPropertiesMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) resolved).entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();
                if (!(value instanceof Map)) {
                    throw new IOException(String.format("Illegal dependency syntax in package recipe. Dependency %s "
                            + "should have a property map, but actually: %s", name, entry.getValue()));
                }
                Map<String, String> propMap = (Map<String, String>) value;
                String versionRequirements = "*";
                String dependencyType = "";
                for (Map.Entry<String, String> e : propMap.entrySet()) {
                    if (e.getValue() == null) {
                        continue;
                    }
                    String k = e.getKey();
                    switch (k.toLowerCase()) {
                        case DEPENDENCY_VERSION_REQUIREMENTS_KEY:
                            versionRequirements = e.getValue();
                            break;
                        case DEPENDENCY_TYPE_KEY:
                            dependencyType = e.getValue();
                            break;
                        default:
                            throw new IOException(String.format("Illegal dependency syntax in package recipe. "
                                    + "Dependency %s has unknown keyword: %s", name, k));
                    }
                }

                if (dependencyType.isEmpty()) {
                    dependencyPropertiesMap.put(name, new RecipeDependencyProperties(versionRequirements));
                    continue;
                }
                dependencyPropertiesMap.put(name, new RecipeDependencyProperties(versionRequirements, dependencyType));
            }
            return dependencyPropertiesMap;
        }
    }
}
