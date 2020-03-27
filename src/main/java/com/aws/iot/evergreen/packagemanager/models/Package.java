/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Collections;
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
    private Semver version;

    private final String description;

    private final String publisher;

    private final Set<PackageParameter> packageParameters;

    private final Map<String, Object> lifecycle;

    // TODO: Migrate to artifact objects, this is only a list of URLs at the moment
    private final List<String> artifacts;

    private final Map<String, String> dependencies;

    // TODO: Needs discussion, this should probably be removed after integration demo
    private final List<String> requires;

    // TODO: clean up this field
    @Deprecated
    @JsonIgnore
    private Set<Package> dependencyPackages;

    /**
     * Constructor for Jackson to deserialize.
     *
     * @param recipeTemplateVersion Template version found in the Recipe file
     * @param packageName           Name of the package
     * @param version               Version of the package
     * @param description           Description metadata
     * @param publisher             Name of the publisher
     * @param packageParameters     Parameters included in the recipe
     * @param lifecycle             Lifecycle definitions
     * @param artifacts             Artifact definitions
     * @param dependencies          List of dependencies
     * @param requires              Package Requires
     * @throws SemverException if the semver fails to be created
     */
    @JsonCreator
    @SuppressWarnings("PMD.ExcessiveParameterList")
    public Package(@JsonProperty("RecipeTemplateVersion") RecipeTemplateVersion recipeTemplateVersion,
                   @JsonProperty("PackageName") String packageName, @JsonProperty("Version") Semver version,
                   @JsonProperty("Description") String description, @JsonProperty("Publisher") String publisher,
                   @JsonProperty("Parameters") Set<PackageParameter> packageParameters,
                   @JsonProperty("Lifecycle") Map<String, Object> lifecycle,
                   @JsonProperty("Artifacts") List<String> artifacts,
                   @JsonProperty("Dependencies") Map<String, String> dependencies,
                   @JsonProperty("Requires") List<String> requires) {
        this.recipeTemplateVersion = recipeTemplateVersion;
        this.packageName = packageName;
        //TODO: Figure out how to do this in deserialize (only option so far seems to be custom deserializer)
        //TODO: Validate SemverType.STRICT before creating this
        this.version = new Semver(version.toString(), Semver.SemverType.NPM);
        this.description = description;
        this.publisher = publisher;
        this.packageParameters = packageParameters == null ? Collections.emptySet() : packageParameters;
        this.lifecycle = lifecycle == null ? Collections.emptyMap() : lifecycle;
        this.artifacts = artifacts == null ? Collections.emptyList() : artifacts;
        this.dependencies = dependencies == null ? Collections.emptyMap() : dependencies;
        this.requires = requires == null ? Collections.emptyList() : requires;
        this.dependencyPackages = new HashSet<>();
    }

    @JsonSerialize(using = SemverSerializer.class)
    public Semver getVersion() {
        return version;
    }
}
