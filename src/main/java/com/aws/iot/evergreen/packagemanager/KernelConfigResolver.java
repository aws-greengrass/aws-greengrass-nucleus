package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KernelConfigResolver {
    private final PackageCache packageCache;

    public KernelConfigResolver(PackageCache packageCache) {
        this.packageCache = packageCache;
    }

    /**
     * Create a kernel config map from a list of package identifiers and deployment document.
     * For each package, it first retrieves its recipe, then merge the parameter values into the recipe, and last
     * transform it to a kernel config key-value pair.
     * @param pkgs a list of package identifiers
     * @param document deployment document
     * @return a kernel config map
     */
    public Map<Object, Object> resolve(List<PackageIdentifier> pkgs, DeploymentDocument document) {
        return new HashMap<>();
    }
}
