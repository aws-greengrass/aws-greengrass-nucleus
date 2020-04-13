/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.InjectionActions;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.plugins.ArtifactDownloader;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.NoArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Named;

@NoArgsConstructor // for dependency injection
public class PackageStore implements InjectionActions {
    private static final Logger logger = LogManager.getLogger(PackageStore.class);
    private static final String RECIPE_DIRECTORY = "recipe";
    private static final String ARTIFACT_DIRECTORY = "artifact";
    private static final String GREENGRASS_SCHEME = "GREENGRASS";
    private static final String VERSION_KEY = "version";
    private static final String PACKAGE_NAME_KEY = "packageName";

    private static final ObjectMapper OBJECT_MAPPER = SerializerFactory.getRecipeSerializer();

    private Path recipeDirectory;

    private Path artifactDirectory;

    @Inject
    private GreengrassRepositoryDownloader greengrassArtifactDownloader;

    @Inject
    private GreengrassPackageServiceHelper greengrassPackageServiceHelper;

    @Inject
    private ExecutorService executorService;

    @Inject
    @Named("packageStoreDirectory")
    private Path packageStoreDirectory;

    @Inject
    private Kernel kernel;

    /**
     * PackageStore constructor.
     *
     * @param packageStoreDirectory directory for caching package recipes and artifacts
     * @param packageServiceHelper  greengrass package service client helper
     * @param artifactDownloader    artifact downloader
     * @param executorService       executor service
     * @param kernel                kernel
     */
    public PackageStore(Path packageStoreDirectory, GreengrassPackageServiceHelper packageServiceHelper,
                        GreengrassRepositoryDownloader artifactDownloader, ExecutorService executorService,
                        Kernel kernel) {
        this.packageStoreDirectory = packageStoreDirectory;
        initializeSubDirectories(packageStoreDirectory);
        this.greengrassPackageServiceHelper = packageServiceHelper;
        this.greengrassArtifactDownloader = artifactDownloader;
        this.executorService = executorService;
        this.kernel = kernel;
    }

    // Workaround using InjectionActions since constructor named pattern injection is not supported yet
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
     * List the package metadata for available package versions that satisfy the requirement.
     * It is ordered by the active version first if found, followed by available versions locally.
     *
     * @param packageName        the package name
     * @param versionRequirement the version requirement for this package
     * @return an iterator of PackageMetadata, with the active version first if found, followed by available versions
     *     locally.
     * @throws PackagingException if fails when trying to list available package metadata
     *
     */
    public Iterator<PackageMetadata> listAvailablePackageMetadata(String packageName, Requirement versionRequirement)
            throws PackagingException {
        // TODO Switch to customized Iterator to enable lazy iteration

        // 1. Find the version if this package is currently active with some version and it is satisfied by requirement
        Optional<PackageMetadata> optionalActivePackageMetadata =
                findActiveAndSatisfiedPackageMetadata(packageName, versionRequirement);

        // 2. list available packages locally
        List<PackageMetadata> packageMetadataList =
                listAvailablePackageMetadataFromLocal(packageName, versionRequirement);

        // 3. If the active satisfied version presents, set it as the head of list.
        if (optionalActivePackageMetadata.isPresent()) {
            PackageMetadata activePackageMetadata = optionalActivePackageMetadata.get();

            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                    .addKeyValue(VERSION_KEY, activePackageMetadata.getPackageIdentifier().getVersion())
                    .log("Found active version for dependency package and it is satisfied by the version requirement."
                            + " Setting it as the head of the available package list.");

            packageMetadataList.remove(activePackageMetadata);
            packageMetadataList.add(0, activePackageMetadata);

        }

        // TODO 4. list available packages from cloud when cloud SDK is ready.


        logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                .addKeyValue("packageMetadataList", packageMetadataList)
                .log("Found possible versions for dependency package");
        return packageMetadataList.iterator();
    }

    /**
     * Make sure all the specified packages exist in the package cache. Download them from remote repository if
     * they don't exist.
     *
     * @param pkgIds a list of packages.
     * @return a future to notify once this is finished.
     */
    public Future<Void> preparePackages(List<PackageIdentifier> pkgIds) {
        return executorService.submit(() -> {
            for (PackageIdentifier packageIdentifier : pkgIds) {
                preparePackage(packageIdentifier);
            }
            return null;
        });
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
                    logger.atError().setCause(e).log(message);
                    throw new RuntimeException(message, e);
                }
            }).collect(Collectors.toList());
            downloadArtifactsIfNecessary(packageIdentifier, artifactURIList);
            logger.atInfo().setEventType("prepare-package-finished").addKeyValue("packageIdentifier", packageIdentifier)
                    .log();
        } catch (PackageLoadingException | PackageDownloadException e) {
            logger.atError().setCause(e).log(String.format("Failed to prepare package %s", packageIdentifier));
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
            savePackageRecipeToFile(pkg, recipePath);
            return pkg;
        }
    }

    /**
     * Get the package recipe with given package identifier.
     *
     * @param pkgId package identifier
     * @return retrieved package recipe.
     * @throws PackageLoadingException if fails to find the target package recipe or failed to load recipe
     */
    public Package getPackageRecipe(PackageIdentifier pkgId) throws PackageLoadingException {
        Optional<Package> optionalPackage = findPackageRecipe(resolveRecipePath(pkgId.getName(), pkgId.getVersion()));

        if (!optionalPackage.isPresent()) {
            // TODO refine exception and logs
            throw new PackageLoadingException(
                    String.format("The recipe for package: '%s' doesn't exist in the local package store.", pkgId));
        }

        return optionalPackage.get();
    }

    Optional<Package> findPackageRecipe(Path recipePath) throws PackageLoadingException {
        logger.atDebug().setEventType("finding-package-recipe").addKeyValue("packageRecipePath", recipePath).log();
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

    void savePackageRecipeToFile(Package pkg, Path saveToFile) throws PackageLoadingException {
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
                        String.format("Failed to create package artifact cache directory %s", packageArtifactDirectory),
                        e);
            }
        }

        List<URI> artifactsNeedToDownload = determineArtifactsNeedToDownload(packageArtifactDirectory, artifactList);
        logger.atDebug().setEventType("downloading-package-artifacts")
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
            return greengrassArtifactDownloader;
        }

        throw new PackageLoadingException(String.format("artifact URI scheme %s is not supported yet", scheme));
    }


    /**
     * Find the active version for a package.
     *
     * @param packageName the package name
     * @return Optional of version; Empty if no active version for this package found.
     */
    private Optional<Semver> findActiveVersion(final String packageName) {
        EvergreenService service;
        try {
            service = kernel.locate(packageName);
        } catch (ServiceLoadException e) {
            logger.atDebug().addKeyValue(PACKAGE_NAME_KEY, packageName)
                    .log("Didn't find a active service for this package running in the kernel.");
            return Optional.empty();
        }
        return Optional.of(getPackageVersionFromService(service));
    }

    /**
     * Get the package version from the active Evergreen service.
     *
     * @param service the active evergreen service
     * @return the package version from the active Evergreen service
     */
    Semver getPackageVersionFromService(final EvergreenService service) {
        Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
        //TODO handle null case
        return new Semver(Coerce.toString(versionTopic));
    }

    /**
     * Find the package metadata for a package if it's active version satisfies the requirement.
     *
     * @param packageName the package name
     * @param requirement the version requirement
     * @return Optional of the package metadata for the package; empty if this package doesn't have active version or
     *     the active version doesn't satisfy the requirement.
     * @throws PackagingException if fails to find the target recipe or parse the recipe
     */
    private Optional<PackageMetadata> findActiveAndSatisfiedPackageMetadata(String packageName, Requirement requirement)
            throws PackagingException {
        Optional<Semver> activeVersionOptional = findActiveVersion(packageName);

        if (!activeVersionOptional.isPresent()) {
            return Optional.empty();
        }

        Semver activeVersion = activeVersionOptional.get();

        if (!requirement.isSatisfiedBy(activeVersion)) {
            return Optional.empty();
        }

        return Optional.of(getPackageMetadata(new PackageIdentifier(packageName, activeVersion)));
    }

    /**
     * list PackageMetadata for available packages that satisfies the requirement.
     *
     * @param packageName the target package
     * @param requirement version requirement
     * @return a list of PackageMetadata that satisfies the requirement.
     * @throws UnexpectedPackagingException if fails to parse version directory to Semver
     */
    private List<PackageMetadata> listAvailablePackageMetadataFromLocal(final String packageName,
                                                                        Requirement requirement)
            throws PackagingException {
        File[] recipeFiles = recipeDirectory.toFile().listFiles();

        if (recipeFiles == null || recipeFiles.length == 0) {
            return Collections.emptyList();
        }

        Arrays.sort(recipeFiles);

        List<PackageMetadata> packageMetadataList = new ArrayList<>();

        for (File recipeFile : recipeFiles) {
            String recipePackageName = parsePackageNameFromFileName(recipeFile.getName());
            // Only check the recipes for the package that we're looking for
            if (!recipePackageName.equalsIgnoreCase(packageName)) {
                continue;
            }

            Semver version = parseVersionFromFileName(recipeFile.getName());
            if (requirement.isSatisfiedBy(version)) {
                packageMetadataList.add(getPackageMetadata(new PackageIdentifier(packageName, version)));
            }
        }

        return packageMetadataList;
    }

    /**
     * Get package metadata for given package name and version.
     *
     * @param pkgId package id
     * @return PackageMetadata; non-null
     * @throws PackagingException if fails to find or parse the recipe
     */
    PackageMetadata getPackageMetadata(PackageIdentifier pkgId) throws PackagingException {
        Package retrievedPackage = getPackageRecipe(pkgId);

        return new PackageMetadata(
                new PackageIdentifier(retrievedPackage.getPackageName(), retrievedPackage.getVersion()),
                retrievedPackage.getDependencies());
    }

    private static String parsePackageNameFromFileName(String filename) {
        // TODO validate filename

        // MonitoringService-1.0.0.yaml
        String suffix = ".yaml";
        String[] packageNameAndVersionParts = filename.split(suffix)[0].split("-");

        return String.join("-", Arrays.copyOf(packageNameAndVersionParts, packageNameAndVersionParts.length - 1));
    }

    private static Semver parseVersionFromFileName(String filename) throws UnexpectedPackagingException {
        // TODO validate filename

        // MonitoringService-1.0.0.yaml
        String suffix = ".yaml";
        String[] packageNameAndVersionParts = filename.split(suffix)[0].split("-");

        // Package name could have '-'. Pick the last part since the version is always after the package name.
        String versionStr = packageNameAndVersionParts[packageNameAndVersionParts.length - 1];

        try {
            return new Semver(versionStr);
        } catch (SemverException e) {
            throw new UnexpectedPackagingException(
                    String.format("Package recipe file name: '%s' is corrupted!", filename), e);
        }
    }

}
