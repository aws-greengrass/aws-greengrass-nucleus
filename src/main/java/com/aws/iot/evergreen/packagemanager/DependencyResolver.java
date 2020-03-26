/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
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
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

@AllArgsConstructor
@NoArgsConstructor
public class DependencyResolver {
    private static final Logger logger = LogManager.getLogger(DependencyResolver.class);
    private static final String ROOT_REQUIREMENT_KEY = "ROOT";
    private static final String VERSION_KEY = "version";
    private static final String PACKAGE_NAME_KEY = "packageName";

    @Setter
    private PackageStore store;
    @Inject
    private Kernel kernel;

    /**
     * Create the full list of packages to be run on the device from a deployment document.
     * It also resolves the conflicts between the packages specified in the deployment document and the existing
     * running packages on the device.
     *
     * @param document        deployment document
     * @param newRootPackages new root level packages
     * @return a list of packages to be run on the device
     * @throws PackageVersionConflictException when a package version conflict cannot be resolved
     * @throws IOException                     when a package cannot be retrieved from the package store
     * @throws PackagingException              for other package errors
     */
    public List<PackageIdentifier> resolveDependencies(final DeploymentDocument document, List<String> newRootPackages)
            throws PackageVersionConflictException, IOException, PackagingException {

        // A map of package version constraints {packageName => {dependingPackageName => versionConstraint}} to be
        // maintained and updated. This information needs to be tracked because: 1. One package can have multiple
        // depending packages posing different version constraints. 2. When the version of a depending package changes,
        // the version constraints will also change accordingly. 3. The information also shows the complete dependency
        // tree.
        Map<String, Map<String, String>> packageNameToVersionConstraints = new HashMap<>();

        // List of root packages to be resolved
        Set<String> rootPackagesToResolve = new LinkedHashSet<>(newRootPackages);

        // Get a list of package configurations with pinned versions
        for (DeploymentPackageConfiguration dpc : document.getDeploymentPackageConfigurationList()) {
            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, dpc.getPackageName())
                    .addKeyValue(VERSION_KEY, dpc.getResolvedVersion()).log("Found package configuration");
            packageNameToVersionConstraints.putIfAbsent(dpc.getPackageName(), new HashMap<>());
            packageNameToVersionConstraints.get(dpc.getPackageName())
                    .put(ROOT_REQUIREMENT_KEY, dpc.getResolvedVersion());
        }

        // Merge the active root packages on the device
        mergeActiveRootPackages(rootPackagesToResolve, packageNameToVersionConstraints);
        logger.atInfo().setEventType("resolve-dependencies-start").addKeyValue("rootPackages", rootPackagesToResolve)
                .addKeyValue("versionConstraints", packageNameToVersionConstraints).log();

        // Map of package name and resolved version
        Map<String, Semver> resolvedPackageNameToVersion = new HashMap<>();

        Optional<String> errorMessage =
                resolveDependencyTree(resolvedPackageNameToVersion, packageNameToVersionConstraints,
                        rootPackagesToResolve);
        if (errorMessage.isPresent()) {
            // Throw error with the last conflict message. More details can be found in full logs.
            throw new PackageVersionConflictException(errorMessage.get());
        }

        logger.atInfo().setEventType("resolve-dependencies-done").addKeyValue("packages", resolvedPackageNameToVersion)
                .log();
        return resolvedPackageNameToVersion.entrySet().stream()
                .map(e -> new PackageIdentifier(e.getKey(), e.getValue())).collect(Collectors.toList());
    }

    /**
     * Implementation of finding one possible solution for package dependency resolution with backtracking. In each
     * call stack, it tries to resolve one package in the packagesToResolve set in Breadth First Search manner, by
     * iterating and exploring each possible version of this package based on the current known version constraints in
     * packageNameToVersionConstraints. When exploring one version of the package, all its unresolved dependency
     * packages will be added to packagesToResolve, and dependency version constraints will be updated to
     * packageNameToVersionConstraints. Eventually resolved package with version will be returned in
     * resolvedPackageNameToVersion, or errors thrown with packagesToResolve of unresolved packages.
     *
     * @param resolvedPackageNameToVersion    map of package names to a pinned version, which is resolved
     * @param packageNameToVersionConstraints map of package names (A) to a map of depending package names (B) to the
     *                                        version requirements (from B to A)
     * @param packagesToResolve               set of package names, which is yet to be resolved
     * @return Optional containing an error message of dependency resolution conflicts, empty if dependency
     *     resolution completes successfully.
     * @throws PackagingException for all package errors
     * @throws IOException        when a package cannot be retrieved from the package store
     */
    private Optional<String> resolveDependencyTree(Map<String, Semver> resolvedPackageNameToVersion,
                                                   Map<String, Map<String, String>> packageNameToVersionConstraints,
                                                   Set<String> packagesToResolve)
            throws PackagingException, IOException, PackageVersionConflictException {
        if (packagesToResolve.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> errorMessage = Optional.empty();

        // Get any one package from the to-be-resolved list
        String pkgName = packagesToResolve.iterator().next();
        logger.atDebug().setEventType("resolve-package-start").addKeyValue(PACKAGE_NAME_KEY, pkgName).log();

        // Compile a list of versions to explore for this package in order
        List<Semver> versionsToExplore = getVersionsToExplore(pkgName, packageNameToVersionConstraints.get(pkgName));
        if (versionsToExplore.isEmpty()) {
            errorMessage = Optional.of(buildErrorMessage(pkgName, resolvedPackageNameToVersion,
                    packageNameToVersionConstraints.get(pkgName)));
        }

        for (Semver version : versionsToExplore) {
            logger.atTrace().setEventType("resolve-package-attempt").addKeyValue(PACKAGE_NAME_KEY, pkgName)
                    .addKeyValue(VERSION_KEY, version).log();

            // Get package recipe
            Package pkgRecipe = getPackage(pkgName, version);

            // Get dependency map (of package name to version constraints) for the current platform
            Map<String, String> dependencyNameToVersionConstraints = getPackageDependencies(pkgRecipe);

            // Note: All changes in Step 1 should be revertible in Step 3
            // 1.1. Generate additional new packages to resolve, which are discovered from dependencies
            Set<String> newDependencyPackagesToResolve = new HashSet<>();

            boolean conflictsInDependency = false;
            // Go over dependency map to see if any has been resolved
            for (Map.Entry<String, String> entry : dependencyNameToVersionConstraints.entrySet()) {
                String depPkgName = entry.getKey();

                if (resolvedPackageNameToVersion.containsKey(depPkgName)) {
                    Semver resolvedVersion = resolvedPackageNameToVersion.get(entry.getKey());
                    String newRequirement = entry.getValue();
                    if (!Requirement.buildNPM(newRequirement).isSatisfiedBy(resolvedVersion)) {
                        // If a dependency package is already resolved, but the version does not satisfy the current
                        // version constraints, there is a conflict.
                        // Try another package version.
                        conflictsInDependency = true;
                        errorMessage = Optional.of(buildErrorMessage(new PackageIdentifier(depPkgName, resolvedVersion),
                                new PackageIdentifier(pkgName, version), newRequirement));

                        logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, depPkgName)
                                .addKeyValue("resolvedVersion", resolvedVersion)
                                .addKeyValue("dependingPackage", pkgName)
                                .addKeyValue("versionConstraints", newRequirement)
                                .log("Resolved package version does not satisfy new version constraints of the "
                                        + "depending package");
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
            logger.atTrace().addKeyValue(PACKAGE_NAME_KEY, pkgName).addKeyValue("packageVersion", version)
                    .addKeyValue("dependencies", newDependencyPackagesToResolve)
                    .log("Found new dependencies to resolve");

            // 1.2. Update all dependency version constraints of this package
            for (Map.Entry<String, String> entry : dependencyNameToVersionConstraints.entrySet()) {
                packageNameToVersionConstraints.putIfAbsent(entry.getKey(), new HashMap<>());
                packageNameToVersionConstraints.get(entry.getKey()).put(pkgName, entry.getValue());
            }

            // 1.3. Resolve current package version
            resolvedPackageNameToVersion.put(pkgName, version);
            packagesToResolve.remove(pkgName);

            // 2. Resolve the rest packages recursively
            Optional<String> optionalErrorMessage =
                    resolveDependencyTree(resolvedPackageNameToVersion, packageNameToVersionConstraints,
                            packagesToResolve);
            if (optionalErrorMessage.isPresent()) {
                errorMessage = optionalErrorMessage;
            } else {
                logger.atDebug().setEventType("resolve-package-done").kv(PACKAGE_NAME_KEY, pkgName)
                        .kv(VERSION_KEY, version).log();
                return Optional.empty();
            }

            // Found conflicts in step 2, so revert all step 1 changes
            // 3.1. Mark current package as unresolved
            packagesToResolve.add(pkgName);
            resolvedPackageNameToVersion.remove(pkgName);

            // 3.2. Remove dependency version constraints of this package
            for (Map.Entry<String, String> entry : dependencyNameToVersionConstraints.entrySet()) {
                packageNameToVersionConstraints.get(entry.getKey()).remove(pkgName);
            }

            // 3.3. Remove newly-introduced dependency packages
            packagesToResolve.removeAll(newDependencyPackagesToResolve);

        }

        logger.atDebug().setEventType("resolve-package-backtrack").addKeyValue(PACKAGE_NAME_KEY, pkgName)
                .log("Exhaust all possible versions of package without a solution. Backtracking...");
        return errorMessage;
    }

    /**
     * Get a ordered list of possible versions to explore for the given package.
     *
     * @param pkgName                     name of the package to be explored
     * @param packageToVersionConstraints list of version constraints for the package
     * @return list of versions as Semver instances
     * @throws UnexpectedPackagingException when a package cannot be retrieved from the package store
     */
    protected List<Semver> getVersionsToExplore(final String pkgName,
                                                final Map<String, String> packageToVersionConstraints)
            throws UnexpectedPackagingException, PackageVersionConflictException {

        List<Semver> versionList = new ArrayList<>();
        logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, pkgName)
                .addKeyValue("versionConstraints", packageToVersionConstraints)
                .log("Parsing version constraints for dependency package");
        Requirement req = Requirement.buildNPM(mergeSemverRequirements(packageToVersionConstraints.values()));

        if (packageToVersionConstraints.containsKey(ROOT_REQUIREMENT_KEY)) {
            // Assume all root packages should use the pinned version.
            Semver pinnedVersion = new Semver(packageToVersionConstraints.get(ROOT_REQUIREMENT_KEY));
            if (!req.isSatisfiedBy(pinnedVersion)) {
                throw new PackageVersionConflictException(String.format(
                        "Conflicts in root package version constraints. Package: %s, version constraints: %s", pkgName,
                        req));
            }
            versionList.add(pinnedVersion);
            return versionList;
        }

        // Add active package version running on the device
        Optional<String> version = getPackageVersionIfActive(pkgName);
        Semver activeVersion = null;
        if (version.isPresent() && req.isSatisfiedBy(version.get())) {
            activeVersion = new Semver(version.get());
            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, pkgName).addKeyValue(VERSION_KEY, activeVersion)
                    .log("Found current active version for dependency package");
            versionList.add(activeVersion);
        }

        // Find out all available versions in package store
        // TODO: Update priorities to be "version available on disk > latest version on the cloud > other versions on
        // the cloud"
        List<Semver> allVersions = store.getPackageVersionsIfExists(pkgName);
        for (Semver v : allVersions) {
            if (req.isSatisfiedBy(v) && !v.equals(activeVersion)) {
                versionList.add(v);
            }
        }
        logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, pkgName).addKeyValue("versionList", versionList)
                .log("Found possible versions for dependency package");
        return versionList;
    }

    protected String mergeSemverRequirements(final Collection<String> packageVersionConstraintList) {
        // TODO: See if there's a better way to get the union of version constraints
        return packageVersionConstraintList.stream().map(Requirement::buildNPM).map(Requirement::toString)
                .collect(Collectors.joining(" "));
    }

    protected Optional<String> getPackageVersionIfActive(final String packageName) {
        EvergreenService service;
        try {
            service = EvergreenService.locate(kernel.context, packageName);
        } catch (ServiceLoadException e) {
            logger.atDebug().setCause(e).addKeyValue(PACKAGE_NAME_KEY, packageName)
                    .log("Failed to get active package in Kernel");
            return Optional.empty();
        }
        return getServiceVersion(service);
    }

    private void mergeActiveRootPackages(Set<String> rootPackagesToResolve,
                                         Map<String, Map<String, String>> packageNameToVersionConstraints) {

        Set<EvergreenService> activeServices = kernel.getMain().getDependencies().keySet();
        for (EvergreenService evergreenService : activeServices) {
            String serviceName = evergreenService.getName();
            // add version constraints for package not in deployment document but is active in device
            if (rootPackagesToResolve.contains(serviceName) && !packageNameToVersionConstraints.keySet()
                    .contains(serviceName)) {
                String version = getServiceVersion(evergreenService).get();
                packageNameToVersionConstraints.putIfAbsent(serviceName, new HashMap<>());
                packageNameToVersionConstraints.get(serviceName).putIfAbsent(ROOT_REQUIREMENT_KEY, version);
                logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, serviceName).addKeyValue(VERSION_KEY, version)
                        .log("Merge active root packages");
            }
        }
    }


    protected Optional<String> getServiceVersion(final EvergreenService service) {
        Node versionNode = service.config.getChild(KernelConfigResolver.VERSION_CONFIG_KEY);
        if (versionNode instanceof Topic) {
            return Optional.of(((Topic) versionNode).getOnce().toString());
        }
        return Optional.empty();
    }

    private Package getPackage(final String pkgName, final Semver version) throws PackagingException, IOException {
        // TODO: handle exceptions with retry
        Optional<Package> optionalPackage = store.getPackage(pkgName, version);
        if (!optionalPackage.isPresent()) {
            throw new UnexpectedPackagingException(
                    String.format("Fail to get details of package %s at version %s", pkgName, version));
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

    private String buildErrorMessage(final String pkgName, final Map<String, Semver> resolvedPackageNameToVersion,
                                     final Map<String, String> versionConstraints) {
        Map<PackageIdentifier, String> pkgIdToVersionRequirements = new HashMap<>();
        versionConstraints.forEach((dependingPkgName, versionRequirement) -> pkgIdToVersionRequirements
                .put(new PackageIdentifier(dependingPkgName, resolvedPackageNameToVersion.get(dependingPkgName)),
                        versionRequirement));
        return String
                .format("Conflicts in resolving package: %s. Version constraints from upstream packages: %s", pkgName,
                        pkgIdToVersionRequirements);
    }

    private String buildErrorMessage(PackageIdentifier pkg, PackageIdentifier dependingPkg, String requirement) {
        return String.format("Package version %s does not satisfy requirements of %s, which is: %s", pkg, dependingPkg,
                requirement);
    }
}
