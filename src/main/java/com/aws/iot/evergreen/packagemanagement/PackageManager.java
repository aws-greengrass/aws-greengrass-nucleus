package com.aws.iot.evergreen.packagemanagement;

import com.aws.iot.evergreen.packagemanagement.model.Package;
import com.aws.iot.evergreen.packagemanagement.model.PackageMetadata;

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
