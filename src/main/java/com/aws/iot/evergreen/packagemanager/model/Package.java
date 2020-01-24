package com.aws.iot.evergreen.packagemanager.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

// not thread safe yet
// should be used in single thread
public class Package {

    private final String serviceName;

    private final String packageName;

    private final String packageVersion;

    private final Map<String, String> lifecycle;

    private final Set<String> artifactUrls;

    private final Set<Dependency> dependencies;

    private final Map<String, Package> dependencyPackageMap;

    public Package(String serviceName, String packageName, String packageVersion, Map<String, String> lifecycle,
                   Set<String> artifactUrls, Set<Dependency> dependencies) {
        this.serviceName = serviceName;
        this.packageName = packageName;
        this.packageVersion = packageVersion;
        this.lifecycle = Collections.unmodifiableMap(lifecycle);
        this.artifactUrls = Collections.unmodifiableSet(artifactUrls);
        this.dependencies = Collections.unmodifiableSet(dependencies);

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

    public Map<String, String> getLifecycle() {
        return lifecycle;
    }

    public Set<String> getArtifactUrls() {
        return artifactUrls;
    }

    public Set<Dependency> getDependencies() {
        return dependencies;
    }

    public Map<String, Package> getDependencyRecipeMap() {
        return dependencyPackageMap;
    }

    public static class Dependency {
        private String packageName;

        private String packageVersion;

        public Dependency(String packageName, String packageVersion) {
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
