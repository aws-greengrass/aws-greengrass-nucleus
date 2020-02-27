/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import com.aws.iot.evergreen.packagemanager.plugins.PackageStore;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PackageManager {

    // TODO: Temporary hard coding, this should be initialized from config
    private static final Path CACHE_DIRECTORY = Paths.get(System.getProperty("user.dir")).resolve("artifact_cache");
    private static final Path MOCK_PACKAGE_SOURCE =
            Paths.get(System.getProperty("user.dir")).resolve("mock_artifact_source");

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    private final PackageRegistry packageRegistry;

    private final PackageStore localCache;

    // TODO: Temporary, should be list of stores
    private final PackageStore mockPackageRepository;

    /**
     * Constructor with hardcoded local cache and mock source paths.
     */
    public PackageManager() {

        this.localCache = new LocalPackageStore(CACHE_DIRECTORY);
        this.mockPackageRepository = new LocalPackageStore(MOCK_PACKAGE_SOURCE);
        this.packageRegistry = new PackageRegistryImpl();
    }

    //TODO mock package store in unit tests, remove this constructor
    @Deprecated
    PackageManager(final PackageRegistry packageRegistry, final Path cacheDirPath, final Path mockDirPath) {

        this.localCache = new LocalPackageStore(cacheDirPath);
        this.mockPackageRepository = new LocalPackageStore(mockDirPath);
        this.packageRegistry = packageRegistry;
    }

    PackageManager(final PackageRegistry packageRegistry, PackageStore localCache, PackageStore mockRepository) {

        this.localCache = localCache;
        this.packageRegistry = packageRegistry;
        this.mockPackageRepository = mockRepository;
    }

    /**
     * Given a set of proposed package dependency trees.
     * Return the local resolved dependency tress in the future
     */
    public Future<Set<Package>> resolvePackages(Set<PackageMetadata> proposedPackages) {
        return executorService.submit(() -> resolveDependencies(proposedPackages));
    }

    private Set<Package> resolveDependencies(Set<PackageMetadata> proposedPackages)
            throws PackageVersionConflictException, PackageDownloadException, PackageLoadingException {
        Map<String, PackageRegistryEntry> activePackages = packageRegistry.findActivePackages().stream()
                .collect(Collectors.toMap(PackageRegistryEntry::getName, Function.identity()));
        Set<PackageRegistryEntry> beforePackageSet = new HashSet<>(activePackages.values());

        for (PackageMetadata proposedPackage : proposedPackages) {
            resolveDependencies(proposedPackage, activePackages);
        }

        Set<PackageRegistryEntry> pendingDownloadPackages =
                activePackages.values().stream().filter(p -> !beforePackageSet.contains(p)).collect(Collectors.toSet());
        //TODO this needs to revisit, do we want one fail all or supporting partial download
        Set<PackageRegistryEntry> downloadedPackages;
        try {
            downloadedPackages = downloadPackages(pendingDownloadPackages);
        } catch (IOException | PackagingException e) {
            throw new PackageDownloadException("not all the packages have been successfully downloaded");
        }
        if (pendingDownloadPackages.size() != downloadedPackages.size()) {
            throw new PackageDownloadException("not all the packages have been successfully downloaded");
        }

        packageRegistry.updateActivePackages(new ArrayList<>(activePackages.values()));

        return loadPackages(proposedPackages, activePackages);
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

    /**
     * Given a set of pending refresh packages, download the package recipes and artifacts in background.
     * Return the packages got successfully downloaded
     */
    public Set<PackageRegistryEntry> downloadPackages(Set<PackageRegistryEntry> pendingDownloadPackages)
            throws IOException, PackagingException {
        // TODO: Trying to match provided API but this should be simplified, too many redundant ops across APIs
        // This can potentially just return packages directly
        Set<PackageRegistryEntry> downloadedPackages = new HashSet<>();
        for (PackageRegistryEntry packageEntry : pendingDownloadPackages) {
            Optional<Package> pkg = mockPackageRepository.getPackage(packageEntry.getName(), packageEntry.getVersion());
            if (pkg.isPresent()) {
                localCache.cachePackageRecipeAndArtifacts(pkg.get());
                downloadedPackages.add(packageEntry);
            }
        }
        return downloadedPackages;
    }

    private Set<Package> loadPackages(Set<PackageMetadata> proposedPackages,
                                      Map<String, PackageRegistryEntry> activePackages) throws PackageLoadingException {
        Set<Package> packages = new HashSet<>();
        Map<PackageRegistryEntry, Package> packageCache = new HashMap<>();
        for (PackageMetadata proposedPackage : proposedPackages) {
            packages.add(loadPackage(proposedPackage.getName(), proposedPackage, activePackages, packageCache));
        }
        return packages;
    }

    Package loadPackage(String name, PackageMetadata packageMetadata, Map<String, PackageRegistryEntry> activePackages,
                        Map<PackageRegistryEntry, Package> packageCache) throws PackageLoadingException {
        PackageRegistryEntry packageEntry = activePackages.get(name);
        if (packageCache.containsKey(packageEntry)) {
            return packageCache.get(packageEntry);
        }

        if (packageEntry == null) {
            throw new PackageLoadingException(String.format("package %s not found in registry", name));
        }
        Optional<Package> packageOptional;
        try {
            packageOptional = localCache.getPackage(packageEntry.getName(), packageEntry.getVersion());
        } catch (Exception e) {
            throw new PackageLoadingException(String.format("failed to load package %s from package store", name), e);
        }

        Package pkg = packageOptional
                .orElseThrow(() -> new PackageLoadingException(String.format("package %s not found", name)));
        pluginParameterValues(pkg, packageMetadata);
        packageCache.put(packageEntry, pkg);

        for (PackageRegistryEntry.Reference dependOn : packageEntry.getDependsOn().values()) {
            pkg.getDependencyPackages().add(loadPackage(dependOn.getName(),
                    getDependencyPackageMetadata(dependOn.getName(), packageMetadata), activePackages, packageCache));
        }
        return pkg;
    }

    private void pluginParameterValues(Package pkg, PackageMetadata packageMetadata) {
        if (packageMetadata != null) {
            packageMetadata.getParameters().forEach(parameterFromDeployment -> {
                if (pkg.getPackageParameters().contains(parameterFromDeployment)) {
                    pkg.getPackageParameters().remove(parameterFromDeployment);
                    pkg.getPackageParameters().add(parameterFromDeployment);
                }
            });
        }
    }

    private PackageMetadata getDependencyPackageMetadata(String dependencyName, PackageMetadata packageMetadata) {
        // Check if we can find this dependency in deployment info
        PackageMetadata dependencyPackageMetadata = null;
        if (packageMetadata != null) {
            for (PackageMetadata dependencyFromDeployment : packageMetadata.getDependsOn()) {
                if (dependencyName.equals(dependencyFromDeployment.getName())) {
                    dependencyPackageMetadata = dependencyFromDeployment;
                    break;
                }
            }
        } else {
            // Resolved depdency subtree is different from the proposed one in deployment,
            // hence dependency package was not found in deployment info
            // TODO : Handle this scenario, where will previously set parameter values come from?
        }
        return dependencyPackageMetadata;
    }

}
