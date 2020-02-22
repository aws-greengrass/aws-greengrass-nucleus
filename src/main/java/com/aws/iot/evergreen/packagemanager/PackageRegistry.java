package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;

import java.util.List;

public interface PackageRegistry {

    List<PackageRegistryEntry> findActivePackages();

    void updateActivePackages(List<PackageRegistryEntry> activePackages);

}
