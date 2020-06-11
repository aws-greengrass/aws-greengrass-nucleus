/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageRecipe {
    private static final String DEPENDENCY_VERSION_REQUIREMENTS_KEY = "versionrequirements";
    private static final String DEPENDENCY_TYPE_KEY = "dependencytype";

    // TODO: Will be used for schema versioning in the future
    private final RecipeTemplateVersion recipeTemplateVersion;

    @EqualsAndHashCode.Include
    private final String packageName;

    @EqualsAndHashCode.Include
    private Semver version;

    private final String description;

    private final String publisher;

    private final Set<PackageParameter> packageParameters;

    private final List<String> platforms;

    private final Map<String, Object> lifecycle;

    // TODO: Migrate to artifact objects, this is only a list of URLs at the moment
    private final Map<String, List<URI>> artifacts;

    private final Map<String, RecipeDependencyProperties> dependencies;

    private final Map<String, Object> customConfig;

    private final Map<String, String> environmentVariables;

    /**
     * Constructor for Jackson to deserialize.
     *
     * @param recipeTemplateVersion Template version found in the Recipe file
     * @param packageName           Name of the package
     * @param version               Version of the package
     * @param description           Description metadata
     * @param publisher             Name of the publisher
     * @param packageParameters     Parameters included in the recipe
     * @param platforms             Platforms supported by the recipe
     * @param lifecycle             Lifecycle definitions
     * @param artifacts             Artifact definitions
     * @param dependencies          List of dependencies
     * @param customConfig          Custom config to store in kernel config store
     * @param environmentVariables  Environment variables for the service
     * @throws SemverException if the semver fails to be created
     */
    @JsonCreator
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public PackageRecipe(@JsonProperty("RecipeTemplateVersion") RecipeTemplateVersion recipeTemplateVersion,
                         @JsonProperty("PackageName") String packageName, @JsonProperty("Version") Semver version,
                         @JsonProperty("Description") String description, @JsonProperty("Publisher") String publisher,
                         @JsonProperty("Parameters") Set<PackageParameter> packageParameters,
                         @JsonProperty("Platforms") List<String> platforms,
                         @JsonProperty("Lifecycle") @JsonDeserialize(
                                 using = MapFieldDeserializer.class) Map<String, Object> lifecycle,
                         @JsonProperty("Artifacts") Map<String, List<URI>> artifacts,
                         @JsonProperty("Dependencies") @JsonDeserialize(
                                 using = DependencyMapDeserializer.class)
                                     Map<String, RecipeDependencyProperties> dependencies,
                         @JsonProperty("CustomConfig") @JsonDeserialize(
                                 using = MapFieldDeserializer.class) Map<String, Object> customConfig,
                         @JsonProperty("EnvironmentVariables") @JsonDeserialize(
                                 using = MapFieldDeserializer.class) Map<String, String> environmentVariables) {

        this.recipeTemplateVersion = recipeTemplateVersion;
        this.packageName = packageName;
        //TODO: Figure out how to do this in deserialize (only option so far seems to be custom deserializer)
        //TODO: Validate SemverType.STRICT before creating this
        this.version = new Semver(version.toString(), Semver.SemverType.NPM);
        this.description = description;
        this.publisher = publisher;
        this.platforms = platforms;
        this.packageParameters = packageParameters == null ? Collections.emptySet() : packageParameters;
        this.lifecycle = lifecycle == null ? Collections.emptyMap() : lifecycle;
        this.artifacts = artifacts == null ? Collections.emptyMap() : artifacts;
        this.dependencies = dependencies == null ? Collections.emptyMap() : dependencies;
        this.customConfig = customConfig == null ? Collections.emptyMap() : customConfig;
        this.environmentVariables = environmentVariables == null ? Collections.emptyMap() : environmentVariables;
    }

    @JsonSerialize(using = SemverSerializer.class)
    public Semver getVersion() {
        return version;
    }

    private static class MapFieldDeserializer extends JsonDeserializer<Map<String, Object>> {
        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
                throws IOException {
            Map<Object, Object> map =
                    SerializerFactory.getRecipeSerializer().convertValue(jsonParser.readValueAsTree(), Map.class);

            return (Map<String, Object>) PlatformResolver.resolvePlatform(map);
        }
    }

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
