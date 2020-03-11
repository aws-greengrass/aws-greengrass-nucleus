package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.PlatformResolver;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.model.DeploymentJobDocument;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageNotFoundException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.PackageStore;
import com.vdurmont.semver4j.Semver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.inject.Inject;

public class DependencyResolver {
    // TODO: Temporary, should be list of stores
    private final PackageStore repo;

    // TODO: temporarily suppress this warning which will be gone after these fields get used.
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "URF_UNREAD_FIELD")
    private final PackageRegistry packageRegistry;
    @Inject
    private Context context;

    public DependencyResolver(PackageRegistry packageRegistry, PackageStore repo) {
        this.packageRegistry = packageRegistry;
        this.repo = repo;
    }

    /**
     * Create the full list of packages to be run on the device from a deployment document.
     * It also resolves the conflicts between the packages specified in the deployment document and the existing
     * running packages on the device.
     *
     * @param document deployment document
     * @return a full list of packages to be run on the device
     * @throws PackageVersionConflictException when a package version conflict cannot be resolved
     * @throws InterruptedException            when the running thread is interrupted
     * @throws IOException
     * @throws PackagingException
     * @throws PackageNotFoundException
     */
    public List<Package> resolveDependencies(DeploymentJobDocument document) throws PackageVersionConflictException,
            InterruptedException, IOException, PackagingException, PackageNotFoundException {

        Set<Package> resolvedPackages = new HashSet<>();
        // packageName => {dependingPackageName => versionConstraint}
        Map<String, Map<String, String>> packageVersionConstraints = new HashMap<>();
        List<String> packagesToResolve = new ArrayList<>();

        for (PackageIdentifier pkgId : document.getRootPackages()) {
            // Get the root package from repository
            Package pkg = getPackage(pkgId.getName(), pkgId.getVersion());
            // Add dependencies to be resolved and update dependency version constraints
            Map<String, String> dep = getPackageDependencies(pkg, packageVersionConstraints, packagesToResolve);

            for (Map.Entry<String, String> entry : dep.entrySet()) {
                packageVersionConstraints.putIfAbsent(entry.getKey(), new HashMap<>())
                        .put(pkg.getPackageName(), entry.getValue());
                packagesToResolve.add(entry.getKey());
            }
            // Mark root packages as resolved
            resolvedPackages.add(pkg);
        }

        boolean resolved = resolveDependencies(document
                .getGroupName(), resolvedPackages, packageVersionConstraints, packagesToResolve);
        if (!resolved) {
            throw new PackageVersionConflictException("Unresolved packages: " + packagesToResolve);
        }

        return resolvedPackages.stream().collect(Collectors.toList());
    }

    private boolean resolveDependencies(String groupName, Set<Package> resolvedPackages,
                                        Map<String, Map<String, String>> packageVersionConstraints,
                                        List<String> packagesToResolve) throws PackagingException, IOException {
        if (packagesToResolve.isEmpty()) {
            return true;
        }
        String pkgName = packagesToResolve.get(0);

        // check if package is already in resolvedPackages,
        // if yes, check if the version match current constraints. yes => true, no => false
        // if no, continue
        Stream<Package> matches = resolvedPackages.stream().filter(p -> p.getPackageName().equals(pkgName));
        if (matches.count() > 0) {
            return matches.anyMatch(p -> p.getVersion()
                    .satisfies(mergeSemvarRequirements(packageVersionConstraints.get(pkgName).values())));
        }

        // Compile a list of versions to explore for this package
        List<Semver> allVersions = repo.getPackageVersionsIfExists(pkgName);
        Set<Semver> versionsExplored = new HashSet<>();

        String req = mergeSemvarRequirements(packageVersionConstraints.get(pkgName).values());
        List<Semver> versionsToExplore = getVersionsToExplore(allVersions, req, versionsExplored, pkgName);

        while (!versionsToExplore.isEmpty()) {
            Semver version = versionsToExplore.get(0);

            versionsExplored.add(version);

            // TODO: handle exceptions with retry
            Package pkg = getPackage(pkgName, version);

            // Get dependency list for the current platform
            Map<String, String> dep = getPackageDependencies(pkg, packageVersionConstraints, packagesToResolve);

            // Tentatively
            resolvedPackages.add(pkg);
            packagesToResolve.remove(pkgName);
            for (Map.Entry<String, String> entry : dep.entrySet()) {
                packageVersionConstraints.putIfAbsent(entry.getKey(), new HashMap<>())
                        .put(pkg.getPackageName(), entry.getValue());
                packagesToResolve.add(entry.getKey());
            }

            if (resolveDependencies(groupName, resolvedPackages, packageVersionConstraints, packagesToResolve)) {
                return true;
            }

            resolvedPackages.remove(pkg);
            packagesToResolve.add(pkgName);
            for (Map.Entry<String, String> entry : dep.entrySet()) {
                packageVersionConstraints.get(entry.getKey()).remove(pkg.getPackageName());
                packagesToResolve.remove(entry.getKey());
            }

            // Update version constraints which are added in downstream calls
            req = mergeSemvarRequirements(packageVersionConstraints.get(pkgName).values());
            versionsToExplore = getVersionsToExplore(allVersions, req, versionsExplored, pkgName);
        }

        return false;
    }

    private List<Semver> getVersionsToExplore(final List<Semver> allVersions, final String requirements,
                                              final Set<Semver> versionsExplored, final String pkgName) {
        Set<Semver> versions = allVersions.stream().filter(v -> v.satisfies(requirements)).collect(Collectors.toSet());
        versions.removeAll(versionsExplored);
        List<Semver> versionList = versions.stream().collect(Collectors.toList());

        // Prepend active package version running on the device
        String v = getPackageVersionIfActive(pkgName);
        if (v != null) {
            versionList.add(0, new Semver(v));
        }

        return versionList;
    }

    private String mergeSemvarRequirements(final Collection<String> packageVersionConstraintList) {
        // TODO: merge list of requirements
        // Load getPackageVersionConstraintsIfActive on the device from other groups
        return "";
    }

    private String getPackageVersionIfActive(String packageName) {
        EvergreenService service = EvergreenService.locate(context, packageName);
        Object version = service.config.getChild("version");
        return version == null ? null : version.toString();
    }

    private String getPackageVersionConstraintsIfActive(String packageName) {
        EvergreenService service = EvergreenService.locate(context, packageName);
        Object versionConstraints = service.config.getChild("versionConstraints");
        // Assume to be map of group name and resolved constraints
        // TODO: Update resolved version constraints for this group after deployment success
        return null;
    }

    private Package getPackage(String pkgName, Semver version) throws PackagingException, IOException {
        // TODO: handle exceptions with retry
        Optional<Package> optionalPackage = repo.getPackage(pkgName, version);
        if (!optionalPackage.isPresent()) {
            throw new UnexpectedPackagingException("Unexpected package version in job document: "
                    + pkgName + "-" + version);
        }
        return optionalPackage.get();
    }

    private Map<String, String> getPackageDependencies(Package pkg,
                                                       Map<String, Map<String, String>> packageVersionConstraints,
                                                       List<String> packagesToResolve)
            throws UnexpectedPackagingException {
        // Get dependency list for the current platform
        Object dependencyListForPlatform = PlatformResolver.resolvePlatform(pkg.getDependencies());
        if (!(dependencyListForPlatform instanceof Map)) {
            throw new UnexpectedPackagingException("Unexpected format of dependency map: " + dependencyListForPlatform);
        }
        return (Map<String, String>) dependencyListForPlatform;
    }
}
