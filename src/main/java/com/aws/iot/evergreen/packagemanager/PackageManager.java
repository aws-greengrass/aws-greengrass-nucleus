/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;
import com.vdurmont.semver4j.Semver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PackageManager {

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final PackageRegistry packageRegistry;

    public PackageManager(PackageRegistry packageRegistry) {
        this.packageRegistry = packageRegistry;
    }

    /**
     * Given a set of proposed package dependency trees.
     * Return the local resolved dependency tress in the future
     */
<<<<<<< HEAD
<<<<<<< HEAD
    public Future<Set<Package>> resolvePackages(Set<PackageMetadata> proposedPackages) {
=======
    public Future<Map<PackageMetadata, Package>> resolvePackages(Set<PackageMetadata> proposedPackages) {
=======
    public Future<Set<Package>> resolvePackages(Set<PackageMetadata> proposedPackages) {
<<<<<<< HEAD
>>>>>>> change to return set instead of map
        this.proposedPackages = proposedPackages;

        return executorService.submit((Callable<Set<Package>>) this::resolvePackages);
=======
        return executorService.submit(() -> resolveDependencies(proposedPackages));
>>>>>>> handle some comments
    }

    /*
     * Given a set of proposed package dependency trees,
     * figure out new package dependencies.
     */
    private Set<Package> resolveDependencies(Set<PackageMetadata> proposedPackages)
            throws PackageVersionConflictException, PackageDownloadException {
        Map<String, PackageRegistryEntry> activePackageList = packageRegistry.findActivePackages().stream()
                .collect(Collectors.toMap(PackageRegistryEntry::getName, Function.identity()));
        Set<PackageRegistryEntry> beforePackageSet = new HashSet<>(activePackageList.values());

        for (PackageMetadata proposedPackage : proposedPackages) {
            resolveDependencies(proposedPackage, activePackageList);
        }

        Set<PackageRegistryEntry> pendingDownloadPackages =
                activePackageList.values().stream().filter(p -> !beforePackageSet.contains(p))
                        .collect(Collectors.toSet());
        Set<PackageRegistryEntry> downloadedPackages = downloadPackages(pendingDownloadPackages);
        //TODO this needs to revisit, do we want one fail all or supporting partial download
        if (pendingDownloadPackages.size() != downloadedPackages.size()) {
            throw new PackageDownloadException("not all the packages have been successfully downloaded");
        }

        packageRegistry.updateActivePackages(new ArrayList<>(activePackageList.values()));

        return loadPackages(proposedPackages.stream().map(PackageMetadata::getName).collect(Collectors.toSet()));
    }

    void resolveDependencies(PackageMetadata packageMetadata, Map<String, PackageRegistryEntry> devicePackages)
            throws PackageVersionConflictException {

        Queue<PackageMetadata> processingQueue = new LinkedList<>();
        processingQueue.add(packageMetadata);

        while (!processingQueue.isEmpty()) {
            PackageMetadata proposedPackage = processingQueue.poll();

            boolean useProposedPackage = true;
            // first, resolve current processing package version
            // check if package exists on the device
            PackageRegistryEntry devicePackage = devicePackages.get(proposedPackage.getName());
            if (devicePackage != null) {
                // if exists, check if meets the proposed package constraint
                Semver devicePackageVersion = devicePackage.getVersion();
                if (devicePackageVersion != null && devicePackageVersion
                        .satisfies(proposedPackage.getVersionConstraint())) {
                    // device version meets the constraint, discard proposed version
                    useProposedPackage = false;
                } else {
                    // device version doesn't meet constraint, need to update
                    // check if proposed version meets existing package dependency constraint
                    for (PackageRegistryEntry.Reference dependsOnBy : devicePackage.getDependsOnBy().values()) {
                        if (!proposedPackage.getVersion().satisfies(dependsOnBy.getConstraint())) {
                            throw new PackageVersionConflictException(
                                    String.format("proposed package %s doesn't meet" + " dependent %s constraint",
                                            proposedPackage, dependsOnBy));
                        }
                    }
                }
            }

            // second, if decide to use proposed package version, it needs to further process its dependencies
            // because it can introduce new dependencies into the tree
            if (useProposedPackage) {
                devicePackage = new PackageRegistryEntry(proposedPackage.getName(), proposedPackage.getVersion(),
                        devicePackage == null ? new HashMap<>() : devicePackage.getDependsOnBy());
                devicePackages.put(proposedPackage.getName(), devicePackage);

                for (PackageMetadata proposedDependency : proposedPackage.getDependsOn()) {
                    // populate current package dependencies, but their versions not decided yet
                    devicePackage.getDependsOn().put(proposedDependency.getName(),
                            new PackageRegistryEntry.Reference(proposedDependency.getName(), null,
                                    proposedDependency.getVersionConstraint()));

                    // update dependency's dependsOn with the current package version, because it's determined
                    PackageRegistryEntry dependencyPackageEntry = devicePackages.get(proposedDependency.getName());
                    if (dependencyPackageEntry == null) {
                        dependencyPackageEntry =
                                new PackageRegistryEntry(proposedDependency.getName(), null, new HashMap<>());
                        devicePackages.put(proposedDependency.getName(), dependencyPackageEntry);
                    }
                    PackageRegistryEntry.Reference dependOnBy =
                            dependencyPackageEntry.getDependsOnBy().get(proposedPackage.getName());
                    if (dependOnBy != null) {
                        dependOnBy.setVersion(proposedPackage.getVersion());
                        dependOnBy.setConstraint(proposedPackage.getVersionConstraint());
                    } else {
                        dependencyPackageEntry.getDependsOnBy().put(proposedPackage.getName(),
                                new PackageRegistryEntry.Reference(proposedPackage.getName(),
                                        proposedPackage.getVersion(), proposedDependency.getVersionConstraint()));
                    }

                    processingQueue.add(proposedDependency);
                }
            }

            // third, update its dependent
            for (PackageRegistryEntry.Reference dependsOnBy : devicePackage.getDependsOnBy().values()) {
                PackageRegistryEntry dependent = devicePackages.get(dependsOnBy.getName());
                PackageRegistryEntry.Reference reference = dependent.getDependsOn().get(devicePackage.getName());
                if (reference.getVersion() == null || !reference.getVersion().isEqualTo(devicePackage.getVersion())) {
                    reference.setVersion(devicePackage.getVersion());
                }
            }
        }
    }

    /*
     * Given a set of pending refresh packages, download the package recipes and artifacts in background
     * Return the packages got successfully downloaded
     */
    private Set<PackageRegistryEntry> downloadPackages(Set<PackageRegistryEntry> pendingDownloadPackages) {
<<<<<<< HEAD
>>>>>>> package manager API definition
        return null;
=======
        return pendingDownloadPackages;
>>>>>>> handle some comments
    }

    /*
     * Given a set of target package names, return their resolved dependency trees with recipe data initialized
     */
    private Set<Package> loadPackages(Set<String> packageNames) {
        return Collections.emptySet();
    }

}
