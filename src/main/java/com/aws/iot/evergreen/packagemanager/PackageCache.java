package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;

import java.util.List;
import java.util.concurrent.Future;

public class PackageCache {

    /**
     * Make sure all the specified packages exist in the package cache. Download them from remote repository if
     * they don't exist.
     * @param pkgs a list of packages.
     * @return a future to notify once this is finished.
     */
    public Future<Void> preparePackages(List<PackageIdentifier> pkgs) {
        // TODO: to be implemented.
        return null;
    }

    /**
     * Retrieve the recipe of a package.
     * @param pkg package identifier
     * @return package recipe
     */
    public Package getRecipe(PackageIdentifier pkg) {
        // TODO: to be implemented.
        return null;
    }
}
