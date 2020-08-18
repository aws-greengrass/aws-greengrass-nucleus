/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;


import com.aws.iot.evergreen.packagemanager.models.PlatformSpecificRecipe;
import com.aws.iot.evergreen.packagemanager.models.RecipeTemplateVersion;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.Data;

import java.util.List;

@Data
public class ComponentRecipe {
    private static final String DEPENDENCY_VERSION_REQUIREMENTS_KEY = "versionrequirements";
    private static final String DEPENDENCY_TYPE_KEY = "dependencytype";

    private final RecipeTemplateVersion recipeTemplateVersion;

    private final String componentName;

    private final String componentType;

    private Semver version;

    private final String description;

    private final String publisher;

    private final List<PlatformSpecificRecipe> platformSpecificRecipes;


    /**
     * Constructor for Jackson to deserialize.
     *
     * @param recipeTemplateVersion Template version found in the Recipe file
     * @param componentName         Name of the component
     * @param version               Version of the package
     * @param description           Description metadata
     * @param publisher             Name of the publisher
     * @throws SemverException if the semver fails to be created
     */
    @JsonCreator
//    @SuppressWarnings("PMD.ExcessiveParameterList")
    //TODO See if we can remove constructor as this becomes simple now
    public ComponentRecipe(@JsonProperty("RecipeTemplateVersion") RecipeTemplateVersion recipeTemplateVersion,
                           @JsonProperty("ComponentName") String componentName, @JsonProperty("Version") Semver version,
                           @JsonProperty("Description") String description, @JsonProperty("Publisher") String publisher,
                           @JsonProperty("Platforms") List<PlatformSpecificRecipe> platformSpecificRecipes) {


        this.recipeTemplateVersion = recipeTemplateVersion;
        this.componentName = componentName;
        //TODO: Validate SemverType.STRICT before creating this
        this.version = new Semver(version.toString(), Semver.SemverType.NPM);
        this.description = description;
        this.publisher = publisher;
        this.componentType = null;  //TODO
        this.platformSpecificRecipes = platformSpecificRecipes;
    }
}
