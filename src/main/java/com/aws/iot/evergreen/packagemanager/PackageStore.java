package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.config.Constants;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnsupportedRecipeFormatException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStoreDeprecated;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressWarnings({"PMD.AvoidPrintStackTrace", "PMD.IdenticalCatchBranches"})
public class PackageStore {
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    private static final ObjectMapper OBJECT_MAPPER = SerializerFactory.getRecipeSerializer();

    /**
     * Get package versions with the most preferred version first.
     * @param pkgName the package name
     * @param versionConstraint a version range
     * @return a iterator for package metadata with the most preferred one first
     */
    Iterator<PackageMetadata> getPackageMetadata(String pkgName, String versionConstraint) {
        // TODO to be implemented
        return null;
    }

    /**
     * Make sure all the specified packages exist in the package cache. Download them from remote repository if
     * they don't exist.
     *
     * @param pkgs a list of packages.
     * @return a future to notify once this is finished.
     */
    @SuppressFBWarnings(value = "NP_NONNULL_PARAM_VIOLATION", justification = "Waiting for package cache "
            + "implementation to be completed")
    public Future<Void> preparePackages(List<PackageIdentifier> pkgs) {
        // TODO: to be implemented.
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        completableFuture.complete(null);
        return completableFuture;
    }

    /**
     * Retrieve the recipe of a package.
     *
     * @param pkg package identifier
     * @return package recipe
     */
    public Package getRecipe(PackageIdentifier pkg) {
        // TODO: to be implemented.
        LocalPackageStoreDeprecated localPackageStore = new LocalPackageStoreDeprecated(LOCAL_CACHE_PATH);
        try {
            return localPackageStore.getPackage(pkg.getName(), pkg.getVersion()).get();
        } catch (PackagingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * Get package from cache if it exists.
     *
     *
     */
    List<Semver> getPackageVersionsIfExists(final String packageName) throws UnexpectedPackagingException {
        Path srcPkgRoot = getPackageStorageRoot(packageName, LOCAL_CACHE_PATH);
        List<Semver> versions = new ArrayList<>();

        if (!Files.exists(srcPkgRoot) || !Files.isDirectory(srcPkgRoot)) {
            return versions;
        }

        File[] versionDirs = srcPkgRoot.toFile().listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) {
            return versions;
        }

        try {
            for (File versionDir : versionDirs) {
                // TODO: Depending on platform, this may need to avoid failures on other things
                versions.add(new Semver(versionDir.getName(), Semver.SemverType.NPM));
            }
        } catch (SemverException e) {
            throw new UnexpectedPackagingException("Package Cache is corrupted! " + e.toString(), e);
        }

        return versions;
    }

    /**
     * Get package from cache if it exists.
     *
     * @return Optional containing package recipe as a String
     */
    Optional<Package> getPackage(final String packageName, final Semver packageVersion)
            throws PackagingException, IOException {
        Optional<String> packageRecipeContent = getPackageRecipe(packageName, packageVersion);
        if (!packageRecipeContent.isPresent()) {
            return Optional.empty();
        }
        try {
            Package pkgRecipe = OBJECT_MAPPER.readValue(packageRecipeContent.get(), Package.class);
            return Optional.ofNullable(pkgRecipe);
        } catch (IOException e) {
            throw new UnsupportedRecipeFormatException(Constants.UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG, e);
        }
    }

    /**
     * Get package from cache if it exists.
     *
     * @return Optional containing package recipe as a String
     */
    private Optional<String> getPackageRecipe(final String packageName, final Semver packageVersion)
            throws PackagingException, IOException {
        Path srcPkgRoot = getPackageVersionStorageRoot(packageName, packageVersion.toString(), LOCAL_CACHE_PATH);

        if (!Files.exists(srcPkgRoot) || !Files.isDirectory(srcPkgRoot)) {
            return Optional.empty();
        }
        // TODO: Move to a Common list of Constants
        Path recipePath = srcPkgRoot.resolve(Constants.RECIPE_FILE_NAME);

        if (!Files.exists(recipePath) && Files.isRegularFile(recipePath)) {
            throw new PackagingException("Package manager cache is corrupt");
            // TODO Take some corrective actions before throwing
        }

        return Optional.of(new String(Files.readAllBytes(recipePath), StandardCharsets.UTF_8));
    }


    private static Path getPackageStorageRoot(final String packageName, final Path cacheFolder) {
        return cacheFolder.resolve(packageName);
    }

    private static Path getPackageVersionStorageRoot(final String packageName, final String packageVersion,
                                                     final Path cacheFolder) {
        return getPackageStorageRoot(packageName, cacheFolder).resolve(packageVersion);
    }
}
