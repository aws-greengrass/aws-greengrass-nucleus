package com.aws.iot.evergreen.packagemanager.models.impl;

import com.aws.iot.evergreen.packagemanager.config.Constants;
import com.aws.iot.evergreen.packagemanager.exceptions.DefaultPlatformConfigNotFoundException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnsupportedRecipeFormatException;
import com.aws.iot.evergreen.packagemanager.models.PackageConfigFormat;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactProvider;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConfigFormat25Jan2020 implements PackageConfigFormat {
    private final Map<SupportedPlatforms25Jan2020, PlatformConfigFormat25Jan2020> platformConfig;
    private final PlatformConfigFormat25Jan2020 mergedPlatformConfig;

    /**
     * Constructor for Deserialize.
     *
     * @param platformConfig Platform config object that was deserialized from Recipe
     * @throws UnsupportedRecipeFormatException       Thrown when parsing fails or config is empty
     * @throws DefaultPlatformConfigNotFoundException Thrown when there's no default platform config in the Recipe
     */
    @JsonCreator
    public ConfigFormat25Jan2020(
            @JsonProperty("Platform") Map<SupportedPlatforms25Jan2020, PlatformConfigFormat25Jan2020> platformConfig)
            throws UnsupportedRecipeFormatException, DefaultPlatformConfigNotFoundException {
        if (platformConfig == null) {
            throw new UnsupportedRecipeFormatException("Platform Config is empty!");
        }
        this.platformConfig = platformConfig;
        this.mergedPlatformConfig = mergeConfigs(platformConfig);
    }

    /**
     * Resolve final config for this device using Default and platform specific configs.
     *
     * @param platformConfig Map of all platform configurations that were present in the Recipe
     * @return Resolved Platform config
     * @throws DefaultPlatformConfigNotFoundException Thrown when there's no default config available
     */
    private static PlatformConfigFormat25Jan2020 mergeConfigs(
            final Map<SupportedPlatforms25Jan2020, PlatformConfigFormat25Jan2020> platformConfig)
            throws DefaultPlatformConfigNotFoundException {
        PlatformConfigFormat25Jan2020 resolvedConfig = platformConfig.get(SupportedPlatforms25Jan2020.DEFAULT);
        if (resolvedConfig == null) {
            // TODO: Do we want to use this as a mechanism to restrict some packages to specific platforms?
            throw new DefaultPlatformConfigNotFoundException(Constants.DEFAULT_CONFIG_NOT_FOUND_EXCEPTION_MSG);
        }

        // TODO: Merge platform specific overrides

        return resolvedConfig;
    }

    @Override
    public Set<ArtifactProvider> getArtifactProviders() {
        return mergedPlatformConfig.getArtifactProviders();
    }

    @Override
    public Map<String, String> getDependencies() {
        return mergedPlatformConfig.getDependencies();
    }

    // TODO: Probably not needed?
    public Map<SupportedPlatforms25Jan2020, PlatformConfigFormat25Jan2020> getFullPlatformConfig() {
        return platformConfig;
    }

    /**
     * Platform config specific to this version of the template.
     */
    @Getter
    public static class PlatformConfigFormat25Jan2020 {
        private final Map<String, Object> lifecycle;

        // TODO: Migrate to deserializing artifact providers
        private final Set<ArtifactProvider> artifactProviders;

        private final Map<String, String> dependencies;

        /**
         * Constructer for Deserialize.
         *
         * @param lifecycle    Map of all lifecycle configurations
         * @param artifacts Artifact URLs (TODO: This will change to artifact providers)
         * @param dependencies Set of all dependencies (name and version range)
         * @param requires     Set of all Required services names
         */
        @JsonCreator
        public PlatformConfigFormat25Jan2020(@JsonProperty("lifecycle") HashMap<String, Object> lifecycle,
                                             @JsonProperty("artifacts") Set<String> artifacts,
                                             @JsonProperty("dependencies") HashMap<String, String> dependencies,
                                             @JsonProperty("requires") List<String> requires) {
            this.lifecycle = lifecycle == null ? Collections.emptyMap() : Collections.unmodifiableMap(lifecycle);
            // TODO: This is a placeholder, migrate to using Artifact Providers in next update
            this.artifactProviders = Collections.emptySet();
            this.dependencies = dependencies == null ? Collections.emptyMap() :
                                Collections.unmodifiableMap(dependencies);
        }
    }

    public enum SupportedPlatforms25Jan2020 {
        @JsonProperty("Default")
        DEFAULT("Default"),
        @JsonProperty("Unix")
        UNIX("Unix"),
        @JsonProperty("Posix")
        POSIX("Posix"),
        @JsonProperty("Linux")
        LINUX("Linux"),
        @JsonProperty("Debian")
        DEBIAN("Debian"),
        @JsonProperty("Windows")
        WINDOWS("Windows"),
        @JsonProperty("Fedora")
        FEDORA("Fedora"),
        @JsonProperty("Ubuntu")
        UBUNTU("Ubuntu"),
        @JsonProperty("MacOS")
        MACOS("MacOS"),
        @JsonProperty("Raspbian")
        RASPBIAN("Raspbian"),
        @JsonProperty("Qnx")
        QNX("Qnx"),
        @JsonProperty("Cygwin")
        CYGWIN("Cygwin"),
        @JsonProperty("FreeBSD")
        FREEBSD("FreeBSD"),
        @JsonProperty("Solaris")
        SOLARIS("Solaris");

        private String platformName;

        SupportedPlatforms25Jan2020(final String platformName) {
            this.platformName = platformName;
        }
    }
}
