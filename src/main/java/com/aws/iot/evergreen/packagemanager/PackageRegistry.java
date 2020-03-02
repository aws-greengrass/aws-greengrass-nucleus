package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;

import java.util.List;

public interface PackageRegistry {

    /**
     * find all the active packages registered.
     *
     * @return list of package registry entry
     */
    List<PackageRegistryEntry> findActivePackages();

    /**
     * update the active packages registered.
     *
     * @param activePackages list of package registry entry to be updated
     */
    void updateActivePackages(List<PackageRegistryEntry> activePackages);

}
