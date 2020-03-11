package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.util.HashMap;
import java.util.Map;

public class KernelConfigResolver {
    // TODO: temporarily suppress this warning which will be gone after these fields get used.
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    private final PackageCache packageCache;

    public KernelConfigResolver(PackageCache packageCache) {
        this.packageCache = packageCache;
    }

    /**
     * Create a kernel config map from a list of package identifiers and deployment document.
     * For each package, it first retrieves its recipe, then merge the parameter values into the recipe, and last
     * transform it to a kernel config key-value pair.
     * @param pkgs a map of package identifiers to version constraints
     * @param document deployment document
     * @return a kernel config map
     * @throws InterruptedException when the running thread is interrupted
     */
    public Map<Object, Object> resolve(Map<PackageIdentifier, String> pkgs, DeploymentDocument document)
            throws InterruptedException {
        return new HashMap<>();
    }
}
