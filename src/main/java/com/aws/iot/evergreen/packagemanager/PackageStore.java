/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
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
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStoreDeprecated;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

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
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;

/**
 * TODO Implement public methods.
 */
@SuppressWarnings({"PMD.AvoidPrintStackTrace", "PMD.IdenticalCatchBranches"})
@NoArgsConstructor // for dependency injection
public class PackageStore implements InjectionActions {
    private static final Logger logger = LogManager.getLogger(PackageStore.class);
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");
    private static final String RECIPE_DIRECTORY = "recipe";
    private static final String ARTIFACT_DIRECTORY = "artifact";
    private static final String GREENGRASS_SCHEME = "GREENGRASS";

    private static final ObjectMapper OBJECT_MAPPER = SerializerFactory.getRecipeSerializer();
    private Path packageStorePath = LOCAL_CACHE_PATH;

    public PackageStore() {
    }

    public PackageStore(Path packageStorePath) {
        this.packageStorePath = packageStorePath;
    }

    private Path recipeDirectory;

    private Path artifactDirectory;

    @Inject
    private ArtifactDownloader greenGrassArtifactDownloader;

    @Inject
    private GreengrassPackageServiceHelper greengrassPackageServiceHelper;

    // Workaround using InjectionActions since constructor named pattern injection is not supported yet
    @Inject
    @Named("packageStoreDirectory")
    private Path packageStoreDirectory;

    /**
     * PackageStore constructor.
     * @param packageStoreDirectory directory for caching package recipes and artifacts
     * @param packageServiceHelper  greengrass package service client helper
     * @param artifactDownloader    artifact downloader
     */
    public PackageStore(Path packageStoreDirectory, GreengrassPackageServiceHelper packageServiceHelper,
                        ArtifactDownloader artifactDownloader) {
        initializeSubDirectories(packageStoreDirectory);
        this.greengrassPackageServiceHelper = packageServiceHelper;
        this.greenGrassArtifactDownloader = artifactDownloader;
    }

    @Override
    public void postInject() {
        initializeSubDirectories(packageStoreDirectory);
    }

    private void initializeSubDirectories(Path packageStoreDirectory) {
        this.recipeDirectory = packageStoreDirectory.resolve(RECIPE_DIRECTORY);
        if (!Files.exists(recipeDirectory)) {
            try {
                Files.createDirectories(recipeDirectory);
            } catch (IOException e) {
                throw new RuntimeException(String.format("Failed to create recipe directory %s", recipeDirectory), e);
            }
        }
        this.artifactDirectory = packageStoreDirectory.resolve(ARTIFACT_DIRECTORY);
        if (!Files.exists(artifactDirectory)) {
            try {
                Files.createDirectories(artifactDirectory);
            } catch (IOException e) {
                throw new RuntimeException(String.format("Failed to create artifact directory %s", artifactDirectory),
                        e);
            }
        }
    }

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
     * @param pkgIds a list of packages.
     * @return a future to notify once this is finished.
     */
<<<<<<< HEAD
<<<<<<< HEAD
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
=======
    public List<CompletableFuture<Boolean>> preparePackages(List<PackageIdentifier> pkgs) {
        return pkgs.stream().map(pkg -> CompletableFuture.supplyAsync(() -> preparePackage(pkg)))
                .collect(Collectors.toList());
>>>>>>> add unit tests
=======
    public Future<Void> preparePackages(List<PackageIdentifier> pkgIds) {
        Runnable preparePackageTask = new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                for (PackageIdentifier packageIdentifier :  pkgIds) {
                    preparePackage(packageIdentifier);
                }
            }
        };

        return CompletableFuture.runAsync(preparePackageTask);
>>>>>>> simplify the prepare package task to be sequential
    }

    private void preparePackage(PackageIdentifier packageIdentifier)
            throws PackageLoadingException, PackageDownloadException {
        logger.atInfo().setEventType("prepare-package-start").addKeyValue("packageIdentifier", packageIdentifier).log();
        try {
            Package pkg = findRecipeDownloadIfNotExisted(packageIdentifier);
            List<URI> artifactURIList = pkg.getArtifacts().stream().map(artifactStr -> {
                try {
                    return new URI(artifactStr);
                } catch (URISyntaxException e) {
                    String message = String.format("artifact URI %s is invalid", artifactStr);
                    logger.atError().log(message, e);
                    throw new RuntimeException(message, e);
                }
            }).collect(Collectors.toList());
            downloadArtifactsIfNecessary(packageIdentifier, artifactURIList);
            logger.atInfo().setEventType("prepare-package-finished").addKeyValue("packageIdentifier", packageIdentifier)
                    .log();
        } catch (PackageLoadingException | PackageDownloadException e) {
            logger.atError().log(String.format("Failed to prepare package %s", packageIdentifier), e);
            throw e;
        }
    }

    private Package findRecipeDownloadIfNotExisted(PackageIdentifier packageIdentifier)
            throws PackageDownloadException, PackageLoadingException {
        Path recipePath = resolveRecipePath(packageIdentifier.getName(), packageIdentifier.getVersion());
        Optional<Package> packageOptional = Optional.empty();
        try {
            packageOptional = findPackageRecipe(recipePath);
        } catch (PackageLoadingException e) {
            logger.atWarn().log(String.format("Failed to load package from %s", recipePath), e);
        }
        if (packageOptional.isPresent()) {
            return packageOptional.get();
        } else {
            Package pkg = greengrassPackageServiceHelper.downloadPackageRecipe(packageIdentifier);
            savePackageToFile(pkg, recipePath);
            return pkg;
        }
    }

    Optional<Package> findPackageRecipe(Path recipePath) throws PackageLoadingException {
        logger.atInfo().setEventType("finding-package-recipe").addKeyValue("packageRecipePath", recipePath).log();
        if (!Files.exists(recipePath) || !Files.isRegularFile(recipePath)) {
            return Optional.empty();
        }

        byte[] recipeContent;
        try {
            recipeContent = Files.readAllBytes(recipePath);
        } catch (IOException e) {
            throw new PackageLoadingException(String.format("Failed to load package recipe at %s", recipePath), e);
        }

        try {
            return Optional.of(OBJECT_MAPPER.readValue(recipeContent, Package.class));
        } catch (IOException e) {
            throw new PackageLoadingException(String.format("Failed to parse package recipe at %s", recipePath), e);
        }
    }

    void savePackageToFile(Package pkg, Path saveToFile) throws PackageLoadingException {
        try {
            OBJECT_MAPPER.writeValue(saveToFile.toFile(), pkg);
        } catch (IOException e) {
            throw new PackageLoadingException(String.format("Failed to save package recipe to %s", saveToFile), e);
        }
    }

    private Path resolveRecipePath(String packageName, Semver packageVersion) {
        return recipeDirectory.resolve(String.format("%s-%s.yaml", packageName, packageVersion.getValue()));
    }

    void downloadArtifactsIfNecessary(PackageIdentifier packageIdentifier, List<URI> artifactList)
            throws PackageLoadingException, PackageDownloadException {
        Path packageArtifactDirectory =
                resolveArtifactDirectoryPath(packageIdentifier.getName(), packageIdentifier.getVersion());
        if (!Files.exists(packageArtifactDirectory) || !Files.isDirectory(packageArtifactDirectory)) {
            try {
                Files.createDirectories(packageArtifactDirectory);
            } catch (IOException e) {
                throw new PackageLoadingException(
                        String.format("Failed to create package artifact cache directory " + "%s",
                                packageArtifactDirectory), e);
            }
        }

        List<URI> artifactsNeedToDownload = determineArtifactsNeedToDownload(packageArtifactDirectory, artifactList);
        logger.atInfo().setEventType("downloading-package-artifacts")
                .addKeyValue("packageIdentifier", packageIdentifier)
                .addKeyValue("artifactsNeedToDownload", artifactsNeedToDownload).log();

        for (URI artifact : artifactsNeedToDownload) {
            ArtifactDownloader downloader = selectArtifactDownloader(artifact);
            try {
                downloader.downloadToPath(packageIdentifier, artifact, packageArtifactDirectory);
            } catch (IOException e) {
                throw new PackageDownloadException(
                        String.format("Failed to download package %s artifact %s", packageIdentifier, artifact), e);
            }
        }
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private List<URI> determineArtifactsNeedToDownload(Path packageArtifactDirectory, List<URI> artifacts) {
        //TODO implement proper idempotency logic to determine what artifacts need to download
        return artifacts;
    }

    private Path resolveArtifactDirectoryPath(String packageName, Semver packageVersion) {
        return artifactDirectory.resolve(packageName).resolve(packageVersion.getValue());
    }

    private ArtifactDownloader selectArtifactDownloader(URI artifactUri) throws PackageLoadingException {
        String scheme = artifactUri.getScheme() == null ? null : artifactUri.getScheme().toUpperCase();
        if (GREENGRASS_SCHEME.equals(scheme)) {
            return greenGrassArtifactDownloader;
        }

        throw new PackageLoadingException(String.format("artifact URI scheme %s is not supported yet", scheme));
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

}
