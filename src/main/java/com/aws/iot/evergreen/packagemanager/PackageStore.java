/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.constants.FileSuffix;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRecipe;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.NonNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;

public class PackageStore {
    private static final Logger logger = LogManager.getLogger(PackageStore.class);

    public static final String CONTEXT_PACKAGE_STORE_DIRECTORY = "packageStoreDirectory";
    public static final String RECIPE_DIRECTORY = "recipes";
    public static final String ARTIFACT_DIRECTORY = "artifacts";
    private static final String RECIPE_FILE_NAME_FORMAT = "%s-%s.yaml";

    private static final ObjectMapper RECIPE_SERIALIZER = SerializerFactory.getRecipeSerializer();

    private final Path recipeDirectory;

    private final Path artifactDirectory;

    /**
     * Constructor. It will initialize both recipe and artifact directory.
     *
     * @param packageStoreDirectory the root path for package store.
     * @throws PackagingException if fails to create recipe or artifact directory.
     */
    @Inject
    public PackageStore(@Named(CONTEXT_PACKAGE_STORE_DIRECTORY) @NonNull Path packageStoreDirectory)
            throws PackagingException {
        this.recipeDirectory = packageStoreDirectory.resolve(RECIPE_DIRECTORY);
        if (!Files.exists(recipeDirectory)) {
            try {
                Files.createDirectories(recipeDirectory);
            } catch (IOException e) {
                throw new PackagingException(String.format("Failed to create recipe directory %s", recipeDirectory), e);
            }
        }
        this.artifactDirectory = packageStoreDirectory.resolve(ARTIFACT_DIRECTORY);
        if (!Files.exists(artifactDirectory)) {
            try {
                Files.createDirectories(artifactDirectory);
            } catch (IOException e) {
                throw new PackagingException(String.format("Failed to create artifact directory %s", artifactDirectory),
                        e);
            }
        }
    }

    /**
     * Creates or updates a package recipe in the package store on the disk.
     *
     * @param packageRecipe package recipe to be create.
     * @throws PackageLoadingException if fails to write the package recipe to disk.
     */
    void savePackageRecipe(@NonNull PackageRecipe packageRecipe) throws PackageLoadingException {
        Path recipePath = resolveRecipePath(packageRecipe.getPackageName(), packageRecipe.getVersion());

        try {
            RECIPE_SERIALIZER.writeValue(recipePath.toFile(), packageRecipe);
        } catch (IOException e) {
            // TODO refine exception
            throw new PackageLoadingException(String.format("Failed to save package recipe to %s", recipePath), e);
        }
    }

    /**
     * Find the target package recipe from package store on the disk.
     *
     * @param pkgId package identifier
     * @return Optional of package recipe; empty if not found.
     * @throws PackageLoadingException if fails to parse the recipe file.
     */
    Optional<PackageRecipe> findPackageRecipe(@NonNull PackageIdentifier pkgId) throws PackageLoadingException {
        Path recipePath = resolveRecipePath(pkgId.getName(), pkgId.getVersion());

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
            // TODO Add validation to validate recipe retried matches pkgId
            return Optional.of(RECIPE_SERIALIZER.readValue(recipeContent, PackageRecipe.class));
        } catch (IOException e) {
            throw new PackageLoadingException(String.format("Failed to parse package recipe at %s", recipePath), e);
        }
    }

    /**
     * Get the package recipe from package store on the disk.
     *
     * @param pkgId package identifier
     * @return retrieved package recipe.
     * @throws PackageLoadingException if fails to find the target package recipe or fails to parse the recipe file.
     */
    PackageRecipe getPackageRecipe(@NonNull PackageIdentifier pkgId) throws PackageLoadingException {
        Optional<PackageRecipe> optionalPackage = findPackageRecipe(pkgId);

        if (!optionalPackage.isPresent()) {
            // TODO refine exception and logs
            throw new PackageLoadingException(
                    String.format("The recipe for package: '%s' doesn't exist in the local package store.", pkgId));
        }

        return optionalPackage.get();
    }

    /**
     * Get package metadata for given package name and version.
     *
     * @param pkgId package id
     * @return PackageMetadata; non-null
     * @throws PackagingException if fails to find or parse the recipe
     */
    PackageMetadata getPackageMetadata(@NonNull PackageIdentifier pkgId) throws PackagingException {
        Map<String, String> dependencyMetadata = new HashMap<>();
        getPackageRecipe(pkgId).getDependencies().forEach((name, prop) ->
                dependencyMetadata.put(name, prop.getVersionRequirements()));
        return new PackageMetadata(pkgId, dependencyMetadata);
    }

    /**
     * list PackageMetadata for available packages that satisfies the requirement.
     *
     * @param packageName the target package
     * @param requirement version requirement
     * @return a list of PackageMetadata that satisfies the requirement.
     * @throws UnexpectedPackagingException if fails to parse version directory to Semver
     */
    List<PackageMetadata> listAvailablePackageMetadata(@NonNull String packageName, @NonNull Requirement requirement)
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
     * Resolve the artifact directory path for a target package id.
     *
     * @param packageIdentifier packageIdentifier
     * @return the artifact directory path for target package.
     */
    public Path resolveArtifactDirectoryPath(@NonNull PackageIdentifier packageIdentifier) {
        return artifactDirectory.resolve(packageIdentifier.getName())
                .resolve(packageIdentifier.getVersion().getValue());
    }

    /**
     * Resolve the recipe file path for a target package id.
     *
     * @param packageIdentifier packageIdentifier
     * @return the recipe file path for target package.
     */
    public Path resolveRecipePath(@NonNull PackageIdentifier packageIdentifier) {
        return resolveRecipePath(packageIdentifier.getName(), packageIdentifier.getVersion());
    }

    private Path resolveRecipePath(String packageName, Semver packageVersion) {
        return recipeDirectory.resolve(String.format(RECIPE_FILE_NAME_FORMAT, packageName, packageVersion.getValue()));
    }

    private static String parsePackageNameFromFileName(String filename) {
        // TODO validate filename

        // MonitoringService-1.0.0.yaml
        String[] packageNameAndVersionParts = filename.split(FileSuffix.YAML_SUFFIX)[0].split("-");

        return String.join("-", Arrays.copyOf(packageNameAndVersionParts, packageNameAndVersionParts.length - 1));
    }

    private static Semver parseVersionFromFileName(String filename) throws PackageLoadingException {
        // TODO validate filename

        // MonitoringService-1.0.0.yaml
        String[] packageNameAndVersionParts = filename.split(FileSuffix.YAML_SUFFIX)[0].split("-");

        // PackageRecipe name could have '-'. Pick the last part since the version is always after the package name.
        String versionStr = packageNameAndVersionParts[packageNameAndVersionParts.length - 1];

        try {
            return new Semver(versionStr);
        } catch (SemverException e) {
            throw new PackageLoadingException(
                    String.format("PackageRecipe recipe file name: '%s' is corrupted!", filename), e);
        }
    }

}
