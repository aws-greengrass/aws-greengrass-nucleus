package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.util.ArrayList;
import java.util.List;

public class DependencyResolver {
    private final PackageRegistry packageRegistry;

    public DependencyResolver(PackageRegistry packageRegistry) {
        this.packageRegistry = packageRegistry;
    }

    /**
     * Create the full list of packages to be run on the device from a deployment document.
     * It also resolves the conflicts between the packages specified in the deployment document and the existing
     * running packages on the device.
     * @param document deployment document
     * @return a full list of packages to be run on the device
     * @throws PackageVersionConflictException when a package version conflict cannot be resolved
     */
    public List<PackageIdentifier> resolveDependencies(DeploymentDocument document) throws PackageVersionConflictException {
        return new ArrayList<>();
    }
}
