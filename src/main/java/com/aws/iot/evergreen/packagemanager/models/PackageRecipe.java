package com.aws.iot.evergreen.packagemanager.models;

import com.aws.iot.evergreen.packagemanager.config.Constants;
import com.aws.iot.evergreen.packagemanager.exceptions.UnsupportedRecipeFormatException;
import com.aws.iot.evergreen.packagemanager.models.impl.ConfigFormat25Jan2020;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactProvider;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.Value;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Value
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
public class PackageRecipe {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper(new YAMLFactory());

    static {
        OBJECT_MAPPER.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    }

    private final RecipeTemplateVersion recipeTemplateVersion;

    private final String packageName;

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

    /**
     * Build package object from Recipe file and register it to the package database.
     *
     * @param recipe Recipe contents
     * @return Package described by the input recipe file
     */
    public static PackageRecipe getPackageObject(String recipe)
            throws UnsupportedRecipeFormatException {
        PackageRecipe pkg = null;
        try {
            pkg = OBJECT_MAPPER.readValue(recipe, PackageRecipe.class);
        } catch (IOException e) {
            throw new UnsupportedRecipeFormatException(Constants.UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG, e);
        }

        return pkg;
    }

    /**
     * Override equals method.
     * @param obj Package to compare this one to
     * @return boolean value indicating whether the two packages have same name and version
     */
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof PackageRecipe)) {
            return false;
        }

        PackageRecipe pkg = (PackageRecipe) obj;
        boolean ret = true;
        if (!pkg.getPackageName().equals(getPackageName())) {
            ret = false;
        } else if (!pkg.getPackageVersion().equals((getPackageVersion()))) {
            ret = false;
        }

        return ret;
    }

    /**
     * Override hashCode method.
     * @return int containing hashcode
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        // TODO: Check against INT_MAX, maybe extend to include other fields and change to lombok?
        // TODO: See if this can be used for local override config as well
        String packageId = packageName + "-" + packageVersion.toString();
        result = prime * result
                + packageId.hashCode();
        return result;
    }

}
