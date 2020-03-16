/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.PackageStore;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

@AllArgsConstructor
public class DependencyResolver {
    private static final Logger logger = LogManager.getLogger(DependencyResolver.class);
    private static final String ROOT_REQUIREMENT_KEY = "ROOT";

    private final PackageStore store;

    @Inject
    private Context context;

    public DependencyResolver(PackageStore store) {
        this.store = store;
    }

    /**
     * Create the full list of packages to be run on the device from a deployment document.
     * It also resolves the conflicts between the packages specified in the deployment document and the existing
     * running packages on the device.
     *
     * @param document deployment document
     * @return a list of packages to be run on the device
     * @throws PackageVersionConflictException when a package version conflict cannot be resolved
     * @throws IOException                     when a package cannot be retrieved from the package store
     * @throws PackagingException              for other package errors
     */
    public List<PackageIdentifier> resolveDependencies(final DeploymentDocument document)
            throws PackageVersionConflictException, IOException, PackagingException {

        // Map of package name and resolved version
        Map<String, Semver> resolvedPackages = new HashMap<>();

        // A map of package version constraints {packageName => {dependingPackageName => versionConstraint}} to be
        // maintained and updated
        Map<String, Map<String, String>> packageVersionConstraints = new HashMap<>();

        // List of root packages to be resolved
        Set<String> packagesToResolve = new LinkedHashSet<>(document.getRootPackages());

        // Get a list of package configurations with pinned versions
        // TODO: accept both pinned version and version constraint
        for (DeploymentPackageConfiguration dpc : document.getDeploymentPackageConfigurationList()) {
            logger.atDebug().addKeyValue("packageName", dpc.getPackageName())
                    .addKeyValue("version", dpc.getResolvedVersion()).log("Found package configuration");
            packageVersionConstraints.putIfAbsent(dpc.getPackageName(), new HashMap<>());
            packageVersionConstraints.get(dpc.getPackageName()).put(ROOT_REQUIREMENT_KEY, dpc.getResolvedVersion());
        }

        boolean resolved = resolve(resolvedPackages, packageVersionConstraints, packagesToResolve);
        if (!resolved) {
            throw new PackageVersionConflictException("Unresolved packages: " + packagesToResolve);
        }

        return resolvedPackages.entrySet().stream().map(e -> new PackageIdentifier(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    /**
     * Implementation of finding one possible solution for package dependency resolution with backtracking.
     *
     * @param resolvedPackages          map of package names to a pinned version, which is resolved
     * @param packageVersionConstraints map of package names (A) to a map of depending package names (B) to the version
     *                                  requirements (from B to A)
     * @param packagesToResolve         set of package names, which is yet to be resolved
     * @return true when one way to resolve the version of all packages is found, false otherwise
     * @throws PackagingException for all package errors
     * @throws IOException        when a package cannot be retrieved from the package store
     */
    protected boolean resolve(Map<String, Semver> resolvedPackages,
                              Map<String, Map<String, String>> packageVersionConstraints,
                              Set<String> packagesToResolve) throws PackagingException, IOException {
        if (packagesToResolve.isEmpty()) {
            return true;
        }

        // Get any one package from the to-be-resolved list
        String pkgName = packagesToResolve.iterator().next();

        // Compile a list of versions to explore for this package in order
        List<Semver> versionsToExplore = getVersionsToExplore(pkgName, packageVersionConstraints.get(pkgName).values());

        for (Semver version : versionsToExplore) {

            // Get package recipe
            Package pkgRecipe = getPackage(pkgName, version);

            // Get dependency map (of package name to version constraints) for the current platform
            Map<String, String> dep = getPackageDependencies(pkgRecipe);

            // Note: All changes in Step 1 should be revertible in Step 3
            // 1.1. Generate additional new packages to resolve, which are discovered from dependencies
            Set<String> newDependencyPackagesToResolve = new HashSet<>();

            boolean conflictsInDependency = false;
            // Go over dependency map to see if any has been resolved
            for (Map.Entry<String, String> entry : dep.entrySet()) {
                String depPkgName = entry.getKey();

                if (resolvedPackages.containsKey(depPkgName)) {
                    if (!Requirement.buildNPM(entry.getValue()).isSatisfiedBy(resolvedPackages.get(entry.getKey()))) {
                        // If a dependency package is already resolved, but the version does not satisfy the current
                        // version constraints, there is a conflict.
                        // Try another package version.
                        conflictsInDependency = true;
                        break;
                    }
                } else if (!packagesToResolve.contains(depPkgName)) {
                    // Only add if not already added. Make the change revertible later
                    newDependencyPackagesToResolve.add(depPkgName);
                }
            }
            if (conflictsInDependency) {
                continue;
            }
            packagesToResolve.addAll(newDependencyPackagesToResolve);

            // 1.2. Update all dependency version constraints of this package
            for (Map.Entry<String, String> entry : dep.entrySet()) {
                packageVersionConstraints.putIfAbsent(entry.getKey(), new HashMap<>());
                packageVersionConstraints.get(entry.getKey()).put(pkgName, entry.getValue());
            }

            // 1.3. Resolve current package version
            resolvedPackages.put(pkgName, version);
            packagesToResolve.remove(pkgName);

            // 2. Resolve the rest packages recursively
            if (resolve(resolvedPackages, packageVersionConstraints, packagesToResolve)) {
                return true;
            }

            // Found conflicts in step 2, so revert all step 1 changes
            // 3.1. Mark current package as unresolved
            packagesToResolve.add(pkgName);
            resolvedPackages.remove(pkgName);

            // 3.2. Remove dependency version constraints of this package
            for (Map.Entry<String, String> entry : dep.entrySet()) {
                packageVersionConstraints.get(entry.getKey()).remove(pkgName);
            }

            // 3.3. Remove newly-introduced dependency packages
            packagesToResolve.removeAll(newDependencyPackagesToResolve);

        }

        return false;
    }

    /**
     * Get a ordered list of possible versions to explore for the given package.
     *
     * @param pkgName                      name of the package to be explored
     * @param packageVersionConstraintList list of version constraints for the package
     * @return list of versions as Semver instances
     * @throws UnexpectedPackagingException when a package cannot be retrieved from the package store
     */
    protected List<Semver> getVersionsToExplore(final String pkgName,
                                                final Collection<String> packageVersionConstraintList)
            throws UnexpectedPackagingException {
        // TODO: Consider package version constraints from other groups/fleets on the device
        List<Semver> versionList = new LinkedList<>();
        Requirement req = Requirement.buildNPM(mergeSemverRequirements(packageVersionConstraintList));

        // Find out all available versions in package store
        List<Semver> allVersions = store.getPackageVersionsIfExists(pkgName);

        // Add active package version running on the device
        Optional<String> version = getPackageVersionIfActive(pkgName);
        Semver activeVersion = null;

        if (version.isPresent() && req.isSatisfiedBy(version.get().split(":")[1])) {
            activeVersion = new Semver(version.get().split(":")[1]);
            versionList.add(activeVersion);
        }

        for (Semver v : allVersions) {
            if (req.isSatisfiedBy(v) && !v.equals(activeVersion)) {
                versionList.add(v);
            }
        }

        return versionList;
    }

    protected String mergeSemverRequirements(final Collection<String> packageVersionConstraintList) {
        // TODO: See if there's a better way to get the union of version constraints
        return packageVersionConstraintList.stream().map(v -> Requirement.buildNPM(v).toString())
                .collect(Collectors.joining(" "));
    }

    protected Optional<String> getPackageVersionIfActive(final String packageName) {
        EvergreenService service = null;
        try {
            service = EvergreenService.locate(context, packageName);
        } catch (ServiceLoadException e) {
            logger.atWarn().setCause(e).addKeyValue("packageName", packageName).log("Fail to load package");
            return Optional.empty();
        }
        Object version = service.config.getChild("version");
        return version == null ? Optional.empty() : Optional.of(version.toString());
    }

    private Package getPackage(final String pkgName, final Semver version) throws PackagingException, IOException {
        // TODO: handle exceptions with retry
        Optional<Package> optionalPackage = store.getPackage(pkgName, version);
        if (!optionalPackage.isPresent()) {
            throw new UnexpectedPackagingException("Unexpected error in job document: package-version doesn't exist "
                    + pkgName + "-" + version);
        }
        return optionalPackage.get();
    }

    // Get dependency map for the current platform
    private Map<String, String> getPackageDependencies(final Package pkg) throws UnexpectedPackagingException {
        return pkg.getDependencies();
        // TODO: Add platform keyword
        //Object dependencyListForPlatform = PlatformResolver.resolvePlatform((Map) pkg.getDependencies());
        //if (!(dependencyListForPlatform instanceof Map)) {
        //   throw new UnexpectedPackagingException("Unexpected format of dependency map: " +
        //   dependencyListForPlatform);
        //}
        //return (Map<String, String>) dependencyListForPlatform;
    }
}
