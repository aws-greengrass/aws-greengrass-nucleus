package com.aws.iot.evergreen.packagemanagement;

import com.aws.iot.evergreen.packagemanagement.model.Package;
import com.aws.iot.evergreen.packagemanagement.model.PackageMetadata;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

//import com.aws.iot.evergreen.packagemanagement.model.PackageRegistryEntry;
//import com.aws.iot.evergreen.packagemanagement.model.PackageVersionConflictException;
//
//import java.util.Collections;

public class PackageManager {

    /*
     * Given a set of proposed package dependency trees.
     * Return the local resolved dependency tress in the future
     */
    public Future<Map<PackageMetadata, Package>> resolvePackages(Set<PackageMetadata> proposedPackages) {
        return null;
    }

    //    /*
    //     * Given a set of proposed package dependency trees,
    //     * figure out new package dependencies.
    //     */
    //    private Set<PackageRegistryEntry> resolveNewPackagesDependencies(Set<PackageMetadata> proposedPackages)
    //            throws PackageVersionConflictException {
    //        return Collections.emptySet();
    //    }
    //
    //    /*
    //     * Given a set of pending refresh packages, download the package recipes and artifacts in background
    //     * Return the packages got successfully downloaded
    //     */
    //    private Set<PackageRegistryEntry> downloadPackages(Set<PackageRegistryEntry> pendingDownloadPackages) {
    //        return null;
    //    }
    //
    //    /*
    //     * Given a set of target packages, return their resolved dependency trees with recipe data initialized
    //     */
    //    private Map<PackageMetadata, Package> loadPackages(Set<PackageMetadata> proposedPackages) {
    //        return Collections.emptyMap();
    //    }
}
