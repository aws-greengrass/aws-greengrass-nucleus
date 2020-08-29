/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.converter;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.packagemanager.common.ComponentParameter;
import com.aws.iot.evergreen.packagemanager.common.ComponentRecipe;
import com.aws.iot.evergreen.packagemanager.common.DependencyProperties;
import com.aws.iot.evergreen.packagemanager.common.PlatformSpecificManifest;
import com.aws.iot.evergreen.packagemanager.common.SerializerFactory;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.models.RecipeDependencyProperties;
import com.aws.iot.evergreen.packagemanager.models.RecipeTemplateVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;

/**
 * This class handles conversion between recipe file contract and device business model. It also resolves platform
 * resolving logic while converting.
 */

@NoArgsConstructor(access = AccessLevel.PRIVATE) // so that it can't be 'new'
public final class RecipeLoader {
    // TODO add logging
    //    private static final Logger logger = LogManager.getLogger(RecipeLoader.class);

    /**
     * Converts from the recipe file with platform resolving.
     *
     * @param recipeFileContent recipe file content
     * @return Optional package recipe
     * @throws PackageLoadingException when failed to convert recipe file.
     */
    public static Optional<PackageRecipe> loadFromFile(String recipeFileContent) throws PackageLoadingException {

        ComponentRecipe componentRecipe;
        try {
            componentRecipe =
                    SerializerFactory.getRecipeSerializer().readValue(recipeFileContent, ComponentRecipe.class);
        } catch (JsonProcessingException e) {
            //TODO move this to common model
            throw new PackageLoadingException(
                    String.format("Failed to parse recipe file content to contract model. Recipe file content: '%s'.",
                            recipeFileContent), e);
        }

        if (componentRecipe.getManifests() == null || componentRecipe.getManifests().isEmpty()) {
            throw new PackageLoadingException(
                    String.format("Recipe file %s-%s.yaml is missing manifests", componentRecipe.getComponentName(),
                            componentRecipe.getVersion().toString()));
        }

        Optional<PlatformSpecificManifest> optionalPlatformSpecificManifest =
                PlatformResolver.findBestMatch(componentRecipe.getManifests());

        if (!optionalPlatformSpecificManifest.isPresent()) {
            return Optional.empty();
        }

        PlatformSpecificManifest platformSpecificManifest = optionalPlatformSpecificManifest.get();

        PackageRecipe packageRecipe = PackageRecipe.builder()
                                                   .componentName(componentRecipe.getComponentName())
                                                   .version(componentRecipe.getVersion())
                                                   .publisher(componentRecipe.getPublisher())
                                                   .recipeTemplateVersion(RecipeTemplateVersion.valueOf(
                                                           componentRecipe.getTemplateVersion().name()))
                                                   .componentType(componentRecipe.getComponentType())
                                                   .dependencies(convertDependencyFromFile(
                                                           platformSpecificManifest.getDependencies()))
                                                   .lifecycle(platformSpecificManifest.getLifecycle())
                                                   .artifacts(convertArtifactsFromFile(
                                                           platformSpecificManifest.getArtifacts()))

                                                   .packageParameters(convertParametersFromFile(
                                                           platformSpecificManifest.getParameters()))
                                                   .build();

        return Optional.of(packageRecipe);
    }

    private static Set<PackageParameter> convertParametersFromFile(List<ComponentParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptySet();
        }
        return parameters.stream()
                         .filter(Objects::nonNull)
                         .map(RecipeLoader::convertParameterFromFile)
                         .collect(Collectors.toSet());
    }

    private static PackageParameter convertParameterFromFile(@Nonnull ComponentParameter parameter) {
        return PackageParameter.builder()
                               .name(parameter.getName())
                               .value(parameter.getValue())
                               .type(PackageParameter.ParameterType.valueOf(parameter.getType().name()))
                               .build();

    }

    private static List<ComponentArtifact> convertArtifactsFromFile(
            List<com.aws.iot.evergreen.packagemanager.common.ComponentArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return Collections.emptyList();
        }
        return artifacts.stream()
                        .filter(Objects::nonNull)
                        .map(RecipeLoader::convertArtifactFromFile)
                        .collect(Collectors.toList());
    }

    private static ComponentArtifact convertArtifactFromFile(
            @Nonnull com.aws.iot.evergreen.packagemanager.common.ComponentArtifact componentArtifact) {
        return ComponentArtifact.builder()
                                .artifactUri(componentArtifact.getUri())
                                .algorithm(componentArtifact.getAlgorithm())
                                .checksum(componentArtifact.getDigest())
                                .unarchive(componentArtifact.getUnarchive())
                                .build();
    }

    private static Map<String, RecipeDependencyProperties> convertDependencyFromFile(
            Map<String, DependencyProperties> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return Collections.emptyMap();
        }
        return dependencies.entrySet()
                           .stream()
                           .filter(Objects::nonNull)
                           .collect(Collectors.toMap(Map.Entry::getKey,
                                   entry -> new RecipeDependencyProperties(entry.getValue().getVersionRequirement(),
                                           entry.getValue().getDependencyType() == null ? "HARD" :
                                                   entry.getValue().getDependencyType())));
    }
}
