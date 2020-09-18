/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.exceptions.UnexpectedPackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.constants.FileSuffix;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.NonNull;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Named;

public class ComponentStore {
    private static final Logger logger = LogManager.getLogger(ComponentStore.class);

    public static final String CONTEXT_PACKAGE_STORE_DIRECTORY = "packageStoreDirectory";
    public static final String RECIPE_DIRECTORY = "recipes";
    public static final String ARTIFACT_DIRECTORY = "artifacts";
    public static final String ARTIFACTS_DECOMPRESSED_DIRECTORY = "artifacts-decompressed";
    private static final String RECIPE_FILE_NAME_FORMAT = "%s-%s.yaml";

    private final Path recipeDirectory;

    private final Path artifactDirectory;

    private final Path artifactsDecompressedDirectory;

    /**
     * Constructor. It will initialize recipe, artifact and artifact unpack directory.
     *
     * @param packageStoreDirectory the root path for package store.
     * @throws PackagingException if fails to create recipe or artifact directory.
     */
    @Inject
    public ComponentStore(@Named(CONTEXT_PACKAGE_STORE_DIRECTORY) @NonNull Path packageStoreDirectory)
            throws PackagingException {
        this.recipeDirectory = packageStoreDirectory.resolve(RECIPE_DIRECTORY);
        this.artifactDirectory = packageStoreDirectory.resolve(ARTIFACT_DIRECTORY);
        this.artifactsDecompressedDirectory = packageStoreDirectory.resolve(ARTIFACTS_DECOMPRESSED_DIRECTORY);
        try {
            Utils.createPaths(recipeDirectory, artifactDirectory, artifactsDecompressedDirectory);
        } catch (IOException e) {
            throw new PackagingException("Failed to create necessary directories for package store", e);
        }
    }

    /**
     * Creates or updates a package recipe in the package store on the disk.
     *
     * @param pkgId         the id for the component
     * @param recipeContent recipe content to save
     * @throws PackageLoadingException if fails to write the package recipe to disk.
     */
    void savePackageRecipe(@NonNull ComponentIdentifier pkgId, String recipeContent) throws PackageLoadingException {
        Path recipePath = resolveRecipePath(pkgId.getName(), pkgId.getVersion());

        try {
            FileUtils.writeStringToFile(recipePath.toFile(), recipeContent);
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
    Optional<ComponentRecipe> findPackageRecipe(@NonNull ComponentIdentifier pkgId) throws PackageLoadingException {
        Path recipePath = resolveRecipePath(pkgId.getName(), pkgId.getVersion());

        logger.atDebug().setEventType("finding-package-recipe").addKeyValue("packageRecipePath", recipePath).log();

        if (!Files.exists(recipePath) || !Files.isRegularFile(recipePath)) {
            return Optional.empty();
        }

        String recipeContent;


        try {
            recipeContent = new String(Files.readAllBytes(recipePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PackageLoadingException(
                    String.format("Failed to read package recipe from disk with path: `%s`", recipePath), e);
        }

        return RecipeLoader.loadFromFile(recipeContent);
    }

    /**
     * Get the package recipe from package store on the disk.
     *
     * @param pkgId package identifier
     * @return retrieved package recipe.
     * @throws PackageLoadingException if fails to find the target package recipe or fails to parse the recipe file.
     */
    ComponentRecipe getPackageRecipe(@NonNull ComponentIdentifier pkgId) throws PackageLoadingException {
        Optional<ComponentRecipe> optionalPackage = findPackageRecipe(pkgId);

        if (!optionalPackage.isPresent()) {
            // TODO refine exception and logs
            throw new PackageLoadingException(
                    String.format("Failed to find usable recipe for current platform: %s, for package: '%s' in the "
                            + "local package store.", PlatformResolver.CURRENT_PLATFORM, pkgId));
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
    ComponentMetadata getPackageMetadata(@NonNull ComponentIdentifier pkgId) throws PackagingException {
        Map<String, String> dependencyMetadata = new HashMap<>();
        getPackageRecipe(pkgId).getDependencies()
                               .forEach((name, prop) -> dependencyMetadata.put(name,
                                       prop.getVersionRequirement().toString()));
        return new ComponentMetadata(pkgId, dependencyMetadata);
    }

    /**
     * list PackageMetadata for available packages that satisfies the requirement.
     *
     * @param packageName the target package
     * @param requirement version requirement
     * @return a list of PackageMetadata that satisfies the requirement.
     * @throws UnexpectedPackagingException if fails to parse version directory to Semver
     */
    List<ComponentMetadata> listAvailablePackageMetadata(@NonNull String packageName, @NonNull Requirement requirement)
            throws PackagingException {
        File[] recipeFiles = recipeDirectory.toFile().listFiles();

        List<ComponentMetadata> componentMetadataList = new ArrayList<>();
        if (recipeFiles == null || recipeFiles.length == 0) {
            return componentMetadataList;
        }

        Arrays.sort(recipeFiles);


        for (File recipeFile : recipeFiles) {
            String recipePackageName = parsePackageNameFromFileName(recipeFile.getName());
            // Only check the recipes for the package that we're looking for
            if (!recipePackageName.equalsIgnoreCase(packageName)) {
                continue;
            }

            Semver version = parseVersionFromFileName(recipeFile.getName());
            if (requirement.isSatisfiedBy(version)) {
                componentMetadataList.add(getPackageMetadata(new ComponentIdentifier(packageName, version)));
            }
        }
        componentMetadataList.sort(null);
        return componentMetadataList;
    }


    /**
     * Resolve the artifact directory path for a target package id.
     *
     * @param componentIdentifier packageIdentifier
     * @return the artifact directory path for target package.
     */
    public Path resolveArtifactDirectoryPath(@NonNull ComponentIdentifier componentIdentifier) {
        return artifactDirectory.resolve(componentIdentifier.getName())
                                .resolve(componentIdentifier.getVersion().getValue());
    }

    /**
     * Resolve the recipe file path for a target package id.
     *
     * @param componentIdentifier packageIdentifier
     * @return the recipe file path for target package.
     */
    public Path resolveRecipePath(@NonNull ComponentIdentifier componentIdentifier) {
        return resolveRecipePath(componentIdentifier.getName(), componentIdentifier.getVersion());
    }

    private Path resolveRecipePath(String packageName, Semver packageVersion) {
        return recipeDirectory.resolve(String.format(RECIPE_FILE_NAME_FORMAT, packageName, packageVersion.getValue()));
    }

    /**
     * Resolve the artifact unpack directory path and creates the directory if absent.
     *
     * @param componentIdentifier packageIdentifier
     * @return artifact unpack directory path
     * @throws PackageLoadingException if un-able to create artifact unpack directory path
     */
    public Path resolveAndSetupArtifactsUnpackDirectory(@NonNull ComponentIdentifier componentIdentifier)
            throws PackageLoadingException {
        Path path = artifactsDecompressedDirectory.resolve(componentIdentifier.getName())
                                                  .resolve(componentIdentifier.getVersion().getValue());
        try {
            Utils.createPaths(path);
            return path;
        } catch (IOException e) {
            throw new PackageLoadingException(
                    "Failed to create artifact unpack directory for " + componentIdentifier.toString(), e);
        }
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
