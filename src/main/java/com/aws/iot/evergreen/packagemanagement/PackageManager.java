package com.aws.iot.evergreen.packagemanagement;

import com.aws.iot.evergreen.packagemanagement.model.PackageMetaData;
import com.aws.iot.evergreen.packagemanagement.model.PackageVersionConflictException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public class PackageManager {

    /*
     * Given a set of proposed package dependency trees,
     * figure out packages need to be refreshed by dependency resolution
     */
    public Set<PackageMetaData> resolvePendingRefreshPackages(Set<PackageMetaData> proposedPackages) throws PackageVersionConflictException {
        return Collections.emptySet();
    }

    /*
     * Given a set of pending refresh packages, download the package recipes and artifacts in background
     */
    public Future<Set<PackageMetaData>> downloadPackages(Set<PackageMetaData> pendingRefreshPackages) {
        return null;
    }

    /*
     * Given a set of target packages, return their resolved dependency trees and recipe data initialized
     */
    public Map<PackageMetaData, Package> loadPackages(Set<PackageMetaData> proposedPackages) {
        return Collections.emptyMap();
    }
}
