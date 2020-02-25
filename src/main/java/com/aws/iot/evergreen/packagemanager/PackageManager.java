package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;

public class PackageManager {

    /*
     * Given a set of proposed package dependency trees.
     * Return the local resolved dependency tress in the future
     */
    public Future<Map<PackageMetadata, Package>> resolvePackages(Set<PackageMetadata> proposedPackages) {
        return null;
    }

}
