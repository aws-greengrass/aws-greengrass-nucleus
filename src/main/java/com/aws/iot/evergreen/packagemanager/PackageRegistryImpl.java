package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;

import java.util.Collections;
import java.util.List;

@Deprecated
public class PackageRegistryImpl implements PackageRegistry {
    @Override
    public List<PackageRegistryEntry> findActivePackages() {
        // to be implemented
        return Collections.emptyList();
    }

    @Override
    public void updateActivePackages(List<PackageRegistryEntry> activePackages) {
        // to be implemented
    }
}
