/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.converter;

import com.amazon.aws.iot.greengrass.component.common.DependencyProperties;
import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.amazon.aws.iot.greengrass.component.common.SerializerFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentParameter;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.models.Permission;
import com.aws.greengrass.componentmanager.models.PermissionType;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.inject.Inject;

/**
 * This class handles conversion between recipe file contract and device business model. It also resolves platform
 * resolving logic while converting.
 */

public class RecipeLoader {
    // GG_NEEDS_REVIEW: TODO:[P41216663]: add logging
    private static final Logger LOGGER = LogManager.getLogger(PlatformResolver.class);

    private final PlatformResolver platformResolver;

    @Inject
    public RecipeLoader(PlatformResolver platformResolver) {
        this.platformResolver = platformResolver;
    }

    /**
     * Parse the recipe content to recipe object.
     * @param recipe recipe content as string
     * @return recipe object
     * @throws PackageLoadingException when there are issues parsing the string
     */
    public static com.amazon.aws.iot.greengrass.component.common.ComponentRecipe parseRecipe(String recipe)
            throws PackageLoadingException {
        try {
            return SerializerFactory.getRecipeSerializer().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(recipe, com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.class);
        } catch (JsonProcessingException e) {
            // TODO: [P41216539]: move this to common model
            throw new PackageLoadingException(
                    String.format("Failed to parse recipe file content to contract model. Recipe file content: '%s'.",
                            recipe), e);
        }
    }

    /**
     * Converts from the recipe file with platform resolving.
     *
     * @param recipeFileContent recipe file content
     * @return Optional package recipe
     * @throws PackageLoadingException when failed to convert recipe file.
     */
    public Optional<ComponentRecipe> loadFromFile(String recipeFileContent) throws PackageLoadingException {

        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe componentRecipe = parseRecipe(recipeFileContent);
        if (componentRecipe.getManifests() == null || componentRecipe.getManifests().isEmpty()) {
            throw new PackageLoadingException(
                    String.format("Recipe file %s-%s.yaml is missing manifests", componentRecipe.getComponentName(),
                            componentRecipe.getComponentVersion().toString()));
        }

        Optional<PlatformSpecificManifest> optionalPlatformSpecificManifest =
                platformResolver.findBestMatch(componentRecipe.getManifests());

        if (!optionalPlatformSpecificManifest.isPresent()) {
            return Optional.empty();
        }

        PlatformSpecificManifest platformSpecificManifest = optionalPlatformSpecificManifest.get();
        Set<String> selectors = collectAllSelectors(componentRecipe.getManifests());

        Map<String, DependencyProperties> dependencyPropertiesMap = new HashMap<>();
        if (componentRecipe.getComponentDependencies() != null) {
            dependencyPropertiesMap.putAll(componentRecipe.getComponentDependencies());
        }

        ComponentRecipe packageRecipe = ComponentRecipe.builder().componentName(componentRecipe.getComponentName())
                .version(componentRecipe.getComponentVersion()).publisher(componentRecipe.getComponentPublisher())
                .recipeTemplateVersion(componentRecipe.getRecipeFormatVersion())
                .componentType(componentRecipe.getComponentType()).dependencies(dependencyPropertiesMap)
                .lifecycle(convertLifecycleFromFile(componentRecipe.getLifecycle(), platformSpecificManifest,
                        selectors))
                .artifacts(convertArtifactsFromFile(platformSpecificManifest.getArtifacts()))
                .componentConfiguration(componentRecipe.getComponentConfiguration())
                .componentParameters(convertParametersFromFile(platformSpecificManifest.getParameters())).build();

        return Optional.of(packageRecipe);
    }

    private static Set<ComponentParameter> convertParametersFromFile(
            List<com.amazon.aws.iot.greengrass.component.common.ComponentParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptySet();
        }
        return parameters.stream().filter(Objects::nonNull).map(RecipeLoader::convertParameterFromFile)
                .collect(Collectors.toSet());
    }

    private static ComponentParameter convertParameterFromFile(
            @Nonnull com.amazon.aws.iot.greengrass.component.common.ComponentParameter parameter) {
        return ComponentParameter.builder().name(parameter.getName()).value(parameter.getValue())
                .type(ComponentParameter.ParameterType.valueOf(parameter.getType().name())).build();

    }

    private static List<ComponentArtifact> convertArtifactsFromFile(
            List<com.amazon.aws.iot.greengrass.component.common.ComponentArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return Collections.emptyList();
        }
        return artifacts.stream().filter(Objects::nonNull).map(RecipeLoader::convertArtifactFromFile)
                .collect(Collectors.toList());
    }

    private static ComponentArtifact convertArtifactFromFile(
            @Nonnull com.amazon.aws.iot.greengrass.component.common.ComponentArtifact componentArtifact) {
        return ComponentArtifact.builder().artifactUri(componentArtifact.getUri())
                .algorithm(componentArtifact.getAlgorithm()).checksum(componentArtifact.getDigest())
                .unarchive(componentArtifact.getUnarchive())
                .permission(convertPermissionFromFile(componentArtifact.getPermission())).build();
    }

    /**
     * Folds all selectors into one set that is specific to this recipe, used for lifecycle filtering.
     * @param manifests Collection of manifests
     * @return Set of all selectors
     */
    private static Set<String> collectAllSelectors(@Nonnull List<PlatformSpecificManifest> manifests) {
        Set<String> allSelectors = new HashSet<>();
        manifests.stream().map(m -> m.getSelections()).filter(s -> s != null).forEach(s -> {
            allSelectors.addAll(s);
        });
        allSelectors.add(PlatformResolver.ALL_KEYWORD); // implicit, it is ok if it was specified explicitly
        return allSelectors;
    }

    /**
     * Performs filtering on a lifecycle map that is manifest specific.
     * @param lifecycleMap Recipe lifecycle map
     * @param manifest     Selected manifest
     * @param allSelectors All selectors defined in this recipe
     * @return filtered lifecycle
     */
    private static Map<String, Object> convertLifecycleFromFile(
            @Nonnull Map<String, Object> lifecycleMap,
            @Nonnull PlatformSpecificManifest manifest,
            @Nonnull Set<String> allSelectors) {

        Map<String, Object> effectiveLifecycleMap = lifecycleMap;

        if (manifest.getSelections() == null || manifest.getSelections().isEmpty()) {
            // BEGIN BETA Compatibility code
            // TODO: These need to be removed for re:Invent
            // We might be running with old lifecycle

            if (effectiveLifecycleMap.isEmpty()) {
                effectiveLifecycleMap = manifest.getLifecycle();
            }
            if (!effectiveLifecycleMap.isEmpty()) {
                Object resolvedPlatformMap = PlatformResolver.resolvePlatform(effectiveLifecycleMap);
                if (resolvedPlatformMap instanceof Map) {
                    effectiveLifecycleMap = (Map<String, Object>) resolvedPlatformMap;
                } else {
                    effectiveLifecycleMap = Collections.emptyMap();
                }
                if (effectiveLifecycleMap.isEmpty()) {
                    LOGGER.warn("Non-empty lifecycle section ignored after (old style) platform selection filtering");
                    return Collections.emptyMap();
                }
            }
            // END BETA Compatibility code
            return effectiveLifecycleMap;
        } else {
            // selections were applied to the lifecycle section
            // we allow the following syntax forms (combined)
            //
            // Lifecycle:
            //    <selector>: (optional)
            //       Section:
            //          <selector>: (optional)
            //              body
            Object filtered = PlatformResolver.filterPlatform(effectiveLifecycleMap, allSelectors,
                    manifest.getSelections()).orElse(Collections.emptyMap());
            if (filtered instanceof Map && !((Map<?, ?>) filtered).isEmpty()) {
                return (Map<String, Object>) filtered;
            } else {
                LOGGER.warn("Non-empty lifecycle section ignored after platform selection filtering");
                return Collections.emptyMap();
            }
        }
    }

    private static Permission convertPermissionFromFile(
            com.amazon.aws.iot.greengrass.component.common.Permission permission) {
        Permission.PermissionBuilder builder = Permission.builder();
        if (permission != null) {
            builder.read(PermissionType.fromString(permission.getRead().name()));
            builder.execute(PermissionType.fromString(permission.getExecute().name()));
        }
        return builder.build();
    }
}
