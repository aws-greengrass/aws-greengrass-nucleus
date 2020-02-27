/* Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Getter
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Package {

    // TODO: Will be used for schema versioning in the future
    private final RecipeTemplateVersion recipeTemplateVersion;

    @EqualsAndHashCode.Include
    private final String packageName;

    @EqualsAndHashCode.Include
    private Semver packageVersion;

    private final String description;

    private final String publisher;

    private final Set<PackageParameter> packageParameters;

    private final Map<String, Object> lifecycle;

    // TODO: Migrate to artifact objects, this is only a list of URLs at the moment
    private final List<String> artifacts;

    private final Map<String, String> dependencies;

    // TODO: Needs discussion, this should probably be removed after integration demo
    private final List<String> requires;

    private Set<Package> dependencyPackages;
    /**
     * Constructor for Deserialize.
     *
     * @param recipeTemplateVersion Template version found in the Recipe file
     * @param packageName Name of the package
     * @param packageVersion Version of the package
     * @param description Description metadata
     * @param publisher Name of the publisher
     * @param packageParameters Parameters included in the recipe
     * @param lifecycle Lifecycle definitions
     * @param artifacts Artifact definitions
     * @param dependencies List of dependencies
     * @param requires Package Requires
     */
    @JsonCreator
    public Package(@JsonProperty("RecipeTemplateVersion") RecipeTemplateVersion recipeTemplateVersion,
                   @JsonProperty("PackageName") String packageName,
                   @JsonProperty("Version") Semver packageVersion,
                   @JsonProperty("Description") String description,
                   @JsonProperty("Publisher") String publisher,
                   @JsonProperty("Parameters") Set<PackageParameter> packageParameters,
                   @JsonProperty("Lifecycle") Map<String, Object> lifecycle,
                   @JsonProperty("Artifacts") List<String> artifacts,
                   @JsonProperty("Dependencies") Map<String, String> dependencies,
                   @JsonProperty("Requires") List<String> requires) throws SemverException {
        this.recipeTemplateVersion = recipeTemplateVersion;
        this.packageName = packageName;
        //TODO: Figure out how to do this in deserialize (only option so far seems to be custom deserializer)
        this.packageVersion = new Semver(packageVersion.toString(), Semver.SemverType.NPM);
        this.description = description;
        this.publisher = publisher;
        this.packageParameters = packageParameters;
        this.lifecycle = lifecycle;
        this.artifacts = artifacts;
        this.dependencies = dependencies;
        this.requires = requires;
        this.dependencyPackages = new HashSet<>();
    }

}
