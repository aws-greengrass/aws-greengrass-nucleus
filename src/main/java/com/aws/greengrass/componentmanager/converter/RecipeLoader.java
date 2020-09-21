/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.converter;

import com.amazon.aws.iot.greengrass.component.common.PlatformSpecificManifest;
import com.amazon.aws.iot.greengrass.component.common.SerializerFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentParameter;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.PlatformResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;
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
    public static Optional<ComponentRecipe> loadFromFile(String recipeFileContent) throws PackageLoadingException {

        com.amazon.aws.iot.greengrass.component.common.ComponentRecipe componentRecipe;
        try {
            componentRecipe =
                    SerializerFactory.getRecipeSerializer()
                            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(recipeFileContent,
                            com.amazon.aws.iot.greengrass.component.common.ComponentRecipe.class);
        } catch (JsonProcessingException e) {
            //TODO move this to common model
            throw new PackageLoadingException(
                    String.format("Failed to parse recipe file content to contract model. Recipe file content: '%s'.",
                            recipeFileContent), e);
        }

        if (componentRecipe.getManifests() == null || componentRecipe.getManifests().isEmpty()) {
            throw new PackageLoadingException(
                    String.format("Recipe file %s-%s.yaml is missing manifests", componentRecipe.getComponentName(),
                            componentRecipe.getComponentVersion().toString()));
        }

        Optional<PlatformSpecificManifest> optionalPlatformSpecificManifest =
                PlatformResolver.findBestMatch(componentRecipe.getManifests());

        if (!optionalPlatformSpecificManifest.isPresent()) {
            return Optional.empty();
        }

        PlatformSpecificManifest platformSpecificManifest = optionalPlatformSpecificManifest.get();


        ComponentRecipe packageRecipe = ComponentRecipe.builder()
                                                   .componentName(componentRecipe.getComponentName())
                                                   .version(componentRecipe.getComponentVersion())
                                                   .publisher(componentRecipe.getComponentPublisher())
                                                   .recipeTemplateVersion(componentRecipe.getRecipeFormatVersion())
                                                   .componentType(componentRecipe.getComponentType())
                                                   .dependencies(platformSpecificManifest.getDependencies())
                                                   .lifecycle(platformSpecificManifest.getLifecycle())
                                                   .artifacts(convertArtifactsFromFile(
                                                           platformSpecificManifest.getArtifacts()))

                                                   .componentParameters(convertParametersFromFile(
                                                           platformSpecificManifest.getParameters()))
                                                   .build();

        return Optional.of(packageRecipe);
    }

    private static Set<ComponentParameter> convertParametersFromFile(
            List<com.amazon.aws.iot.greengrass.component.common.ComponentParameter> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptySet();
        }
        return parameters.stream()
                         .filter(Objects::nonNull)
                         .map(RecipeLoader::convertParameterFromFile)
                         .collect(Collectors.toSet());
    }

    private static ComponentParameter convertParameterFromFile(
            @Nonnull com.amazon.aws.iot.greengrass.component.common.ComponentParameter parameter) {
        return ComponentParameter.builder()
                               .name(parameter.getName())
                               .value(parameter.getValue())
                               .type(ComponentParameter.ParameterType.valueOf(parameter.getType().name()))
                               .build();

    }

    private static List<ComponentArtifact> convertArtifactsFromFile(
            List<com.amazon.aws.iot.greengrass.component.common.ComponentArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return Collections.emptyList();
        }
        return artifacts.stream()
                        .filter(Objects::nonNull)
                        .map(RecipeLoader::convertArtifactFromFile)
                        .collect(Collectors.toList());
    }

    private static ComponentArtifact convertArtifactFromFile(
            @Nonnull com.amazon.aws.iot.greengrass.component.common.ComponentArtifact componentArtifact) {
        return ComponentArtifact.builder()
                                .artifactUri(componentArtifact.getUri())
                                .algorithm(componentArtifact.getAlgorithm())
                                .checksum(componentArtifact.getDigest())
                                .unarchive(componentArtifact.getUnarchive())
                                .build();
    }
}
