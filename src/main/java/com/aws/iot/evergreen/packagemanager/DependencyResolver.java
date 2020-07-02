/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;

@AllArgsConstructor
@NoArgsConstructor
public class DependencyResolver {
    private static final Logger logger = LogManager.getLogger(DependencyResolver.class);
    private static final String VERSION_KEY = "version";
    private static final String PACKAGE_NAME_KEY = "packageName";

    @Inject
    private PackageManager packageManager;

    @Inject
    private Kernel kernel;

    /**
     * Create the full list of packages to be run on the device from a deployment document.
     * It also resolves the conflicts between the packages specified in the deployment document and the existing
     * running packages on the device.
     *
     * @param document                   deployment document
     * @param groupToRootPackagesDetails {@link Topics} providing package details for each group
     * @return a list of packages to be run on the device
     * @throws PackageVersionConflictException when a package version conflict cannot be resolved
     * @throws IOException                     when a package cannot be retrieved from the package store
     * @throws PackagingException              for other package errors
     */
    public List<PackageIdentifier> resolveDependencies(final DeploymentDocument document,
                                                       Topics groupToRootPackagesDetails)
            throws PackageVersionConflictException, IOException, PackagingException {

        // A map of package version constraints {packageName => {dependingPackageName => versionConstraint}} to be
        // maintained and updated. This information needs to be tracked because: 1. One package can have multiple
        // depending packages posing different version constraints. 2. When the version of a depending package changes,
        // the version constraints will also change accordingly. 3. The information also shows the complete dependency
        // tree.
        Map<String, Map<String, String>> packageNameToVersionConstraints = new HashMap<>();
        Set<String> rootPackagesToResolve = new LinkedHashSet<>();

        // Get a list of all package configurations with version constraints in the deployment document
        for (DeploymentPackageConfiguration dpc : document.getDeploymentPackageConfigurationList()) {
            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, dpc.getPackageName())
                    .addKeyValue(VERSION_KEY, dpc.getResolvedVersion()).log("Found package configuration");
            packageNameToVersionConstraints.putIfAbsent(dpc.getPackageName(), new HashMap<>());

            // Only the group to package mapping before this deployment is guaranteed to be available, since config
            // updates happen in a separate thread.
            if (document.getRootPackages().contains(dpc.getPackageName())) {
                rootPackagesToResolve.add(dpc.getPackageName());
            }
            packageNameToVersionConstraints.get(dpc.getPackageName())
                    .put(document.getGroupName(), dpc.getResolvedVersion());
        }

        //Get package version constraints for root packages corresponding to other groups
        updatePackageConstraintsFromOtherGroups(groupToRootPackagesDetails, document.getGroupName(),
                rootPackagesToResolve, packageNameToVersionConstraints);

        logger.atInfo().setEventType("resolve-dependencies-start")
                .addKeyValue("versionConstraints", packageNameToVersionConstraints)
                .kv("RootPackagesToResolve", rootPackagesToResolve).kv("DeploymentGroup", document.getGroupName())
                .log("The root packages for deployment");

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

    private void updatePackageConstraintsFromOtherGroups(Topics groupToRootPackagesDetails, String deploymentGroupName,
                                                          Set<String> rootPackagesToResolve,
                                                          Map<String, Map<String, String>>
                                                                  packageNameToVersionConstraints) {

        //Get package version constraints for root packages corresponding to other groups
        groupToRootPackagesDetails.forEach(node -> {
            Topics groupTopics = (Topics) node;
            String groupName = groupTopics.getName();
            if (!groupName.equals(deploymentGroupName)) {
                groupTopics.forEach(pkgTopic -> {
                    rootPackagesToResolve.add(pkgTopic.getName());
                    packageNameToVersionConstraints.putIfAbsent(pkgTopic.getName(), new HashMap<>());
                    Map<Object, Object> pkgDetails = (Map) pkgTopic.toPOJO();
                    packageNameToVersionConstraints.get(pkgTopic.getName())
                            .putIfAbsent(groupName, pkgDetails.get(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY).toString());
                });
            }
        });
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
     *         resolution completes successfully.
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
        Map<String, String> versionConstraints = packageNameToVersionConstraints.get(pkgName);

        logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, pkgName).addKeyValue("versionConstraints", versionConstraints)
                .log("Parsing version constraints for dependency package");

        Requirement req = Requirement.buildNPM(mergeSemverRequirements(versionConstraints.values()));

        Iterator<PackageMetadata> versionsToExplore = packageManager.listAvailablePackageMetadata(pkgName, req);

        if (!versionsToExplore.hasNext()) {
            errorMessage = Optional.of(buildErrorMessage(pkgName, resolvedPackageNameToVersion,
                    packageNameToVersionConstraints.get(pkgName)));
        }

        while (versionsToExplore.hasNext()) {
            PackageMetadata packageMetadata = versionsToExplore.next();
            logger.atTrace().setEventType("resolve-package-attempt").addKeyValue(PACKAGE_NAME_KEY, pkgName)
                    .addKeyValue(VERSION_KEY, packageMetadata.getPackageIdentifier().getVersion()).log();

            // Get dependency map (of package name to version constraints) for the current platform
            Map<String, String> dependencyNameToVersionConstraints = packageMetadata.getDependencies();

            // Note: All changes in Step 1 should be revertible in Step 3
            // 1.1. Generate additional new packages to resolve, which are discovered from dependencies
            Set<String> newDependencyPackagesToResolve = new HashSet<>();

            boolean conflictsInDependency = false;
            // Go over dependency map to see if any has been resolved
            for (Map.Entry<String, String> entry : dependencyNameToVersionConstraints.entrySet()) {
                String depPkgName = entry.getKey();

                if (resolvedPackageNameToVersion.containsKey(depPkgName)) {
                    Semver resolvedVersion = resolvedPackageNameToVersion.get(depPkgName);
                    String newRequirement = entry.getValue();
                    if (!Requirement.buildNPM(newRequirement).isSatisfiedBy(resolvedVersion)) {
                        // If a dependency package is already resolved, but the version does not satisfy the current
                        // version constraints, there is a conflict.
                        // Try another package version.
                        conflictsInDependency = true;
                        errorMessage = Optional.of(buildErrorMessage(new PackageIdentifier(depPkgName, resolvedVersion),
                                packageMetadata.getPackageIdentifier(), newRequirement));

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
            logger.atTrace().addKeyValue(PACKAGE_NAME_KEY, pkgName)
                    .addKeyValue(VERSION_KEY, packageMetadata.getPackageIdentifier().getVersion())
                    .addKeyValue("dependencies", newDependencyPackagesToResolve)
                    .log("Found new dependencies to resolve");

            // 1.2. Update all dependency version constraints of this package
            for (Map.Entry<String, String> entry : dependencyNameToVersionConstraints.entrySet()) {
                packageNameToVersionConstraints.putIfAbsent(entry.getKey(), new HashMap<>());
                packageNameToVersionConstraints.get(entry.getKey()).put(pkgName, entry.getValue());
            }

            // 1.3. Resolve current package version
            resolvedPackageNameToVersion.put(pkgName, packageMetadata.getPackageIdentifier().getVersion());
            packagesToResolve.remove(pkgName);

            // 2. Resolve the rest packages recursively
            Optional<String> optionalErrorMessage =
                    resolveDependencyTree(resolvedPackageNameToVersion, packageNameToVersionConstraints,
                            packagesToResolve);
            if (optionalErrorMessage.isPresent()) {
                errorMessage = optionalErrorMessage;
            } else {
                logger.atDebug().setEventType("resolve-package-done").kv(PACKAGE_NAME_KEY, pkgName)
                        .kv(VERSION_KEY, packageMetadata.getPackageIdentifier().getVersion()).log();
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

    String mergeSemverRequirements(final Collection<String> packageVersionConstraintList) {
        // TODO: See if there's a better way to get the intersection of version constraints
        return packageVersionConstraintList.stream().map(Requirement::buildNPM).map(Requirement::toString)
                .collect(Collectors.joining(" "));
    }

    private String buildErrorMessage(final String pkgName, final Map<String, Semver> resolvedPackageNameToVersion,
                                     final Map<String, String> versionConstraints) {
        Map<String, String> pkgIdToVersionRequirements = new HashMap<>();
        versionConstraints.forEach((dependingPkgName, versionRequirement) -> {
            Semver dependingPkgVersion = resolvedPackageNameToVersion.get(dependingPkgName);
            if (dependingPkgVersion == null) {
                pkgIdToVersionRequirements.put(dependingPkgName, versionRequirement);
            } else {
                pkgIdToVersionRequirements
                        .put(new PackageIdentifier(dependingPkgName, resolvedPackageNameToVersion.get(dependingPkgName))
                                .toString(), versionRequirement);
            }
        });
        return String
                .format("Conflicts in resolving package: %s. Version constraints from upstream packages: %s", pkgName,
                        pkgIdToVersionRequirements);
    }

    private String buildErrorMessage(PackageIdentifier pkg, PackageIdentifier dependingPkg, String requirement) {
        return String.format("Package version %s does not satisfy requirements of %s, which is: %s", pkg, dependingPkg,
                requirement);
    }
}
