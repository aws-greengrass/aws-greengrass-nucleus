package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.packagemanager.models.impl.ConfigFormat25Jan2020;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactProvider;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Value;

import java.util.Map;
import java.util.Set;

@Value
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PackageRecipe {

    private final RecipeTemplateVersion recipeTemplateVersion;

    @EqualsAndHashCode.Include
    private final String packageName;

    @EqualsAndHashCode.Include
    private Semver packageVersion;

    private final String description;

    private final String publisher;

    // TODO: May be more maintainable as a custom deserializer?? This was way faster to get started with
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "RecipeTemplateVersion",
            visible = true)
    @JsonSubTypes({
            @JsonSubTypes.Type(value = ConfigFormat25Jan2020.class, name = "2020-01-25")
    })
    @JsonProperty("Config")
    private final PackageConfigFormat config;

    /**
     * Constructor for Deserialize.
     *
     * @param recipeTemplateVersion Template version found in the Recipe file
     * @param packageName Name of the package
     * @param packageVersion Version of the package
     * @param description Description metadata
     * @param publisher Name of the publisher
     * @param config Configuration for this package
     */
    @JsonCreator
    public PackageRecipe(@JsonProperty("RecipeTemplateVersion") RecipeTemplateVersion recipeTemplateVersion,
                         @JsonProperty("PackageName") String packageName,
                         @JsonProperty("Version") Semver packageVersion,
                         @JsonProperty("Description") String description,
                         @JsonProperty("Publisher") String publisher,
                         @JsonProperty("Config") PackageConfigFormat config) throws SemverException {
        this.recipeTemplateVersion = recipeTemplateVersion;
        this.packageName = packageName;
        //TODO: Figure out how to do this in deserialize (only option so far seems to be custom deserializer)
        this.packageVersion = new Semver(packageVersion.toString(), Semver.SemverType.NPM);
        this.description = description;
        this.publisher = publisher;
        this.config =  config;
    }

    public Set<ArtifactProvider> getArtifactProviders() {
        return config.getArtifactProviders();
    }

    public Map<String, String> getDependencies() {
        return config.getDependencies();
    }
}
