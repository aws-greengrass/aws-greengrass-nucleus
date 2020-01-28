package com.aws.iot.evergreen.packagemanager.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// not thread safe yet
// should be used in single thread
public class Package {

    private final String serviceName;

    private final String packageName;

    private final String packageVersion;

    private final Map<String, Object> lifecycle;

    private final Set<String> artifactUrls;

    private final Set<Dependency> dependencies;

    private final Map<String, Package> dependencyPackageMap;

    @JsonCreator
    public Package(@JsonProperty("service") String serviceName, @JsonProperty("name") String packageName,
                   @JsonProperty("version") String packageVersion,
                   @JsonProperty("lifecycle") Map<String, Object> lifecycle,
                   @JsonProperty("artifacts") List<String> artifactUrls,
                   @JsonProperty("dependencies") Set<Dependency> dependencies) {
        this.serviceName = serviceName;
        this.packageName = packageName;
        this.packageVersion = packageVersion;
        this.lifecycle = lifecycle == null ? Collections.emptyMap() : Collections.unmodifiableMap(lifecycle);
        this.artifactUrls = artifactUrls == null ? Collections.emptySet() : new HashSet<>(artifactUrls);
        this.dependencies = dependencies == null ? Collections.emptySet() : Collections.unmodifiableSet(dependencies);

        this.dependencyPackageMap = new HashMap<>();
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getPackageName() {
        return packageName;
    }

    public String getPackageVersion() {
        return packageVersion;
    }

    public Map<String, Object> getLifecycle() {
        return lifecycle;
    }

    public Set<String> getArtifactUrls() {
        return artifactUrls;
    }

    public Set<Dependency> getDependencies() {
        return dependencies;
    }

    public Map<String, Package> getDependencyPackageMap() {
        return dependencyPackageMap;
    }

    public static class Dependency {
        private String packageName;

        private String packageVersion;

        @JsonCreator
        public Dependency(@JsonProperty("name") String packageName, @JsonProperty("version") String packageVersion) {
            this.packageName = packageName;
            this.packageVersion = packageVersion;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getPackageVersion() {
            return packageVersion;
        }

    }


}
