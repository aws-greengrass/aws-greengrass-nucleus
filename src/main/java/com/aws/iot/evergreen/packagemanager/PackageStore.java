/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.config.Constants;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnsupportedRecipeFormatException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactDownloader;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStoreDeprecated;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * TODO Implement public methods.
 */
@SuppressWarnings({"PMD.AvoidPrintStackTrace", "PMD.IdenticalCatchBranches"})
public class PackageStore {
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    private static final ObjectMapper OBJECT_MAPPER = SerializerFactory.getRecipeSerializer();
    private Path packageStorePath = LOCAL_CACHE_PATH;

    public PackageStore() {
    }

    public PackageStore(Path packageStorePath) {
        this.packageStorePath = packageStorePath;
    }

    private Path recipeDirectory;

    private Path artifactDirectory;

    /**
     * Get package versions with the most preferred version first.
     *
     * @param pkgName           the package name
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
<<<<<<< HEAD
=======
    @SuppressFBWarnings(value = "NP_NONNULL_PARAM_VIOLATION",
            justification = "Waiting for package cache " + "implementation to be completed")
>>>>>>> package store download with s3 presigned url
    public Future<Void> preparePackages(List<PackageIdentifier> pkgs) {
        // TODO: to be implemented.
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        completableFuture.complete(null);
        return completableFuture;
    }

    private void preparePackage(PackageIdentifier packageIdentifier)
            throws PackageLoadingException, PackageDownloadException {
        Path recipePath = resolveRecipePath(packageIdentifier.getName(), packageIdentifier.getVersion());
        Package pkg = findPackageRecipe(recipePath);
        if (pkg == null) {
            pkg = downloadPackageRecipe(packageIdentifier.getArn(), recipePath);
        }
        downloadArtifactsIfNecessary(packageIdentifier, pkg.getArtifacts());
    }

    private Package findPackageRecipe(Path packageRecipe) throws PackageLoadingException {
        byte[] recipeContent = loadPackageRecipeContent(packageRecipe);
        if (recipeContent == null) {
            return null;
        }

        try {
            return OBJECT_MAPPER.readValue(recipeContent, Package.class);
        } catch (IOException e) {
            throw new PackageLoadingException(Constants.UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG, e);
        }
    }

    private byte[] loadPackageRecipeContent(Path packageRecipe) throws PackageLoadingException {
        if (!Files.exists(packageRecipe) || !Files.isRegularFile(packageRecipe)) {
            return null;
        }

        try {
            return Files.readAllBytes(packageRecipe);
        } catch (IOException e) {
            throw new PackageLoadingException("error", e);
        }
    }

    private Package downloadPackageRecipe(String packageArn, Path saveToFile)
            throws PackageDownloadException {
        //TODO retrieve package recipe from cloud
        //To pretend it working, load from local now
        Package pkg;
        try {
            pkg = findPackageRecipe(LOCAL_CACHE_PATH.resolve(String.format("%s.yaml", packageArn)));
        } catch (PackageLoadingException e) {
            throw new PackageDownloadException("error", e);
        }

        try {
            OBJECT_MAPPER.writeValue(new File(saveToFile.toString()), pkg);
        } catch (IOException e) {
            throw new PackageDownloadException("error", e);
        }

        return pkg;
    }

    private Path resolveRecipePath(String packageName, Semver packageVersion) {
        return recipeDirectory.resolve(String.format("%s-%s.yaml", packageName, packageVersion.getValue()));
    }

    private void downloadArtifactsIfNecessary(PackageIdentifier packageIdentifier, List<String> artifactList)
            throws PackageLoadingException, PackageDownloadException {
        if (artifactList.isEmpty()) {
            return;
        }

        Path packageArtifactDirectory = resolveArtifactDirectoryPath(packageIdentifier.getName(),
                packageIdentifier.getVersion());
        if (Files.exists(packageArtifactDirectory) && Files.isDirectory(packageArtifactDirectory)) {
            return;
        }

        for (String artifact : artifactList) {
            URI artifactUri;
            try {
                artifactUri = new URI(artifact);
            } catch (URISyntaxException e) {
                throw new PackageLoadingException("error", e);
            }

            ArtifactDownloader downloader = selectArtifactDownloader(artifactUri);
            try {
                downloader.downloadArtifactToPath(packageIdentifier, artifactUri, packageArtifactDirectory);
            } catch (IOException e) {
                throw new PackageDownloadException("error", e);
            }
        }
    }

    private Path resolveArtifactDirectoryPath(String packageName, Semver packageVersion) {
        return artifactDirectory.resolve(String.format("%s-%s", packageName, packageVersion.getValue()));
    }

    private ArtifactDownloader selectArtifactDownloader(URI artifactUri) throws PackageLoadingException {
        String scheme = artifactUri.getScheme();
        if (scheme == null) {
            throw new PackageLoadingException("error");
        }

        try {
            return ArtifactProvider.valueOf(scheme.toUpperCase()).getArtifactDownloader();
        } catch (IllegalArgumentException e) {
            throw new PackageLoadingException("error", e);
        }
    }

    /**
     * Retrieve the recipe of a package.
     *
     * @param pkg package identifier
     * @return package recipe
     */
    public Package getRecipe(PackageIdentifier pkg) {
        // TODO: to be implemented.
        LocalPackageStoreDeprecated localPackageStore = new LocalPackageStoreDeprecated(packageStorePath);
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
     */
    List<Semver> getPackageVersionsIfExists(final String packageName) throws UnexpectedPackagingException {
        Path srcPkgRoot = getPackageStorageRoot(packageName, packageStorePath);
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
        Path srcPkgRoot = getPackageVersionStorageRoot(packageName, packageVersion.toString(), packageStorePath);

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

    private enum ArtifactProvider {
        GREENGRASS(new GreengrassRepositoryDownloader());

        private ArtifactDownloader artifactDownloader;

        ArtifactProvider(ArtifactDownloader artifactDownloader) {
            this.artifactDownloader = artifactDownloader;
        }

        public ArtifactDownloader getArtifactDownloader() {
            return artifactDownloader;
        }
    }
}
