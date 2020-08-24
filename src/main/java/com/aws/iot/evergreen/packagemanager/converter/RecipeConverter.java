/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.converter;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.common2.ComponentRecipe;
import com.aws.iot.evergreen.packagemanager.common2.DependencyProperties;
import com.aws.iot.evergreen.packagemanager.common2.PlatformSpecificManifest;
import com.aws.iot.evergreen.packagemanager.common2.SerializerFactory;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.models.ComponentArtifact;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.packagemanager.models.RecipeDependencyProperties;
import com.aws.iot.evergreen.packagemanager.models.RecipeTemplateVersion;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * This class handles conversion between recipe file contract and device business model. It also resolves platform
 * resolving logic while converting.
 */
public class RecipeConverter {

    private static final Logger logger = LogManager.getLogger(RecipeConverter.class);


    /**
     * @param recipeFileContent
     * @return
     * @throws PackageLoadingException
     */
    public Optional<PackageRecipe> convertFromFile(String recipeFileContent) throws PackageLoadingException {

        ComponentRecipe componentRecipe;
        try {
            componentRecipe =
                    SerializerFactory.getRecipeSerializer().readValue(recipeFileContent, ComponentRecipe.class);
        } catch (JsonProcessingException e) {
            throw new PackageLoadingException(
                    String.format("Failed to parse recipe file content to contract model. Recipe file content: '%s'.",
                            recipeFileContent), e);
        }

        Optional<PlatformSpecificManifest> optionalPlatformSpecificManifest =
                PlatformResolver.findBestMatch(componentRecipe.getManifests());

        if (!optionalPlatformSpecificManifest.isPresent()) {
            return Optional.empty();
        }

        PlatformSpecificManifest platformSpecificManifest = optionalPlatformSpecificManifest.get();

        PackageRecipe packageRecipe = PackageRecipe.builder().componentName(componentRecipe.getComponentName())
                .version(componentRecipe.getVersion()).publisher(componentRecipe.getPublisher())
                .recipeTemplateVersion(RecipeTemplateVersion.valueOf(componentRecipe.getTemplateVersion().name()))
                .dependencies(convertDependencyFromFile(platformSpecificManifest.getDependencies()))
                .artifacts(convertArtifactsFromFile(platformSpecificManifest.getArtifacts())).lifecycle(platformSpecificManifest.getLifecycle())
                .packageParameters(platformSpecificManifest.getParameters()).build();

        return Optional.of(packageRecipe);
    }

    private Map<String, List<ComponentArtifact>> convertArtifactsFromFile(List<com.aws.iot.evergreen.packagemanager.common2.ComponentArtifact> artifacts) {
        // TODO change master's signature.
        return null;
    }

    private Map<String, RecipeDependencyProperties> convertDependencyFromFile(
            Map<String, DependencyProperties> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return Collections.emptyMap();
        }
        return dependencies.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                entry -> new RecipeDependencyProperties(entry.getValue().getVersionRequirement(),
                        entry.getValue().getDependencyType())));
    }
}
