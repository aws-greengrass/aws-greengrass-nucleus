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
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.constants.FileSuffix;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.SerializerFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;

public class ComponentStore {

    public static final String RECIPE_DIRECTORY = "recipes";
    public static final String ARTIFACT_DIRECTORY = "artifacts";
    public static final String ARTIFACTS_DECOMPRESSED_DIRECTORY = "artifacts-unarchived";
    public static final String RECIPE_FILE_NAME_FORMAT = "%s-%s.yaml";

    private static final Logger logger = LogManager.getLogger(ComponentStore.class);
    private static final String RECIPE_METADATA_FILE_PATH_LOG_KEY = "RecipeMetadataFilePath";

    private final NucleusPaths nucleusPaths;
    private final PlatformResolver platformResolver;
    private final RecipeLoader recipeLoader;

    /**
     * Constructor. It will initialize recipe, artifact and artifact decompressed directory.
     * @param nucleusPaths path library
     * @param platformResolver platform resolver
     * @param recipeLoader recipe loader
     */
    @Inject
    public ComponentStore(NucleusPaths nucleusPaths, PlatformResolver platformResolver, RecipeLoader recipeLoader) {
        this.nucleusPaths = nucleusPaths;
        this.platformResolver = platformResolver;
        this.recipeLoader = recipeLoader;
    }

    /**
     * Creates or updates a package recipe in the package store on the disk.
     *
     * @param pkgId         the id for the component
     * @param recipeContent recipe content to save
     * @throws PackageLoadingException if fails to write the package recipe to disk.
     */
    void savePackageRecipe(@NonNull ComponentIdentifier pkgId, String recipeContent) throws PackageLoadingException {
        try {
            Path recipePath = resolveRecipePath(pkgId.getName(), pkgId.getVersion());
            FileUtils.writeStringToFile(recipePath.toFile(), recipeContent);
        } catch (IOException e) {
            // TODO: [P41215929]: Better logging and exception messages in component store
            throw new PackageLoadingException("Failed to save package recipe", e);
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
        Optional<String> recipeContent = findComponentRecipeContent(pkgId);

        return recipeContent.isPresent() ? recipeLoader.loadFromFile(recipeContent.get()) : Optional.empty();
    }

    /**
     * Validate whether given digest matches the component recipe on disk.
     *
     * @param componentIdentifier component whose recipe is read from disk
     * @param expectedDigest      expected digest for the recipe
     * @return whether the expected digest matches the calculated digest on disk
     */
    public boolean validateComponentRecipeDigest(@NonNull ComponentIdentifier componentIdentifier,
            String expectedDigest) {
        try {
            Optional<String> recipeContent = findComponentRecipeContent(componentIdentifier);
            if (!recipeContent.isPresent()) {
                logger.atError("plugin-load-error")
                        .kv(GreengrassService.SERVICE_NAME_KEY, componentIdentifier.getName())
                        .log("Recipe not found for component " + componentIdentifier.getName());
                return false;
            }
            String digest = Digest.calculate(recipeContent.get());
            logger.atInfo("plugin-load").log("Digest from store: " + Coerce.toString(expectedDigest));
            logger.atInfo("plugin-load").log("Digest from recipe: " + Coerce.toString(digest));
            if (!Digest.isEqual(digest, expectedDigest)) {
                logger.atError("plugin-load-error")
                        .kv(GreengrassService.SERVICE_NAME_KEY, componentIdentifier.getName())
                        .log("Recipe on disk was modified after it was downloaded from cloud");
                return false;
            }
            return true;
        } catch (PackageLoadingException | NoSuchAlgorithmException e) {
            logger.atError("plugin-load-error").kv(GreengrassService.SERVICE_NAME_KEY, componentIdentifier.getName())
                    .log("Cannot validate digest for recipe");
        }
        return false;
    }

    Optional<String> findComponentRecipeContent(@NonNull ComponentIdentifier componentId)
            throws PackageLoadingException {
        Path recipePath = resolveRecipePath(componentId.getName(), componentId.getVersion());

        logger.atDebug().setEventType("finding-package-recipe").addKeyValue("packageRecipePath", recipePath).log();

        if (!Files.exists(recipePath) || !Files.isRegularFile(recipePath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(new String(Files.readAllBytes(recipePath), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PackageLoadingException(
                    String.format("Failed to read package recipe from disk with path: `%s`", recipePath), e);
        }
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
            // TODO: [P41215929]: Better logging and exception messages in component store
            throw new PackageLoadingException(String.format(
                    "Failed to find usable recipe for current platform: %s, for package: '%s' in the "
                            + "local package store.", platformResolver.getCurrentPlatform(), pkgId));
        }

        return optionalPackage.get();
    }

    /**
     * Delete the component recipe, artifacts, and decompressed files from disk.
     *
     * @param compId component identifier
     * @throws PackageLoadingException if deletion of the component failed
     */
    void deleteComponent(@NonNull ComponentIdentifier compId) throws PackageLoadingException {
        logger.atInfo("delete-component-start").kv("componentIdentifier", compId).log();
        IOException exception = null;
        // delete recipe
        try {
            Path recipePath = resolveRecipePath(compId.getName(), compId.getVersion());
            Files.deleteIfExists(recipePath);
        } catch (IOException e) {
            exception = e;
        }
        // delete recipeMetadata
        try {
            Files.deleteIfExists(resolveRecipeMetadataFile(compId).toPath());
        } catch (IOException e) {
            exception = e;
        }
        // delete artifacts
        try {
            Path artifactDirPath = resolveArtifactDirectoryPath(compId);
            FileUtils.deleteDirectory(artifactDirPath.toFile());
        } catch (IOException e) {
            if (exception == null) {
                exception = e;
            } else {
                exception.addSuppressed(e);
            }
        }
        // delete decompressed files
        try {
            Path artifactDecompressedDirPath = nucleusPaths.unarchiveArtifactPath(compId);
            FileUtils.deleteDirectory(artifactDecompressedDirPath.toFile());
        } catch (IOException e) {
            if (exception == null) {
                exception = e;
            } else {
                exception.addSuppressed(e);
            }
        }
        if (exception != null) {
            throw new PackageLoadingException("Failed to delete package " + compId, exception);
        }
        logger.atInfo("delete-component-finish").kv("componentIdentifier", compId).log();
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
                .forEach((name, prop) -> dependencyMetadata.put(name, prop.getVersionRequirement().toString()));
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
        File[] recipeFiles = getAllRecipeFiles();

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
     * Get all locally available component-version by checking the existence of its artifact directory.
     *
     * @return map from component name to a set of version strings in Semver format
     */
    public Map<String, Set<String>> listAvailableComponentVersions() {
        Map<String, Set<String>> result = new HashMap<>();
        File[] compDirs = nucleusPaths.artifactPath().toFile().listFiles(File::isDirectory);
        if (compDirs == null || compDirs.length == 0) {
            return result;
        }

        for (File compDir : compDirs) {
            Set<String> versions = new HashSet<>();
            File[] versionDirs = compDir.listFiles(File::isDirectory);
            if (versionDirs == null) {
                continue;
            }
            for (File versionDir : versionDirs) {
                versions.add(versionDir.getName());
            }
            result.put(compDir.getName(), versions);
        }

        return result;
    }

    Optional<ComponentIdentifier> findBestMatchAvailableComponent(@NonNull String componentName,
            @NonNull Requirement requirement) throws PackageLoadingException {
        File[] recipeFiles = getAllRecipeFiles();

        if (recipeFiles.length == 0) {
            return Optional.empty();
        }

        Arrays.sort(recipeFiles);

        List<ComponentIdentifier> componentIdentifierList = new ArrayList<>();
        for (File recipeFile : recipeFiles) {
            String recipeComponentName = parsePackageNameFromFileName(recipeFile.getName());

            if (!recipeComponentName.equalsIgnoreCase(componentName)) {
                continue;
            }

            Semver version = parseVersionFromFileName(recipeFile.getName());
            if (requirement.isSatisfiedBy(version)) {
                componentIdentifierList.add(new ComponentIdentifier(componentName, version));
            }
        }
        componentIdentifierList.sort(null);

        if (componentIdentifierList.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(componentIdentifierList.get(0));
        }
    }

    private File[] getAllRecipeFiles() {
        // TODO Identify recipes by *.recipe.yaml or *.recipe.json
        return Arrays.stream(nucleusPaths.recipePath().toFile().listFiles())
                .filter(file -> file.getName().endsWith(".yaml"))
                .toArray(File[]::new);
    }


    /**
     * Resolve the artifact directory path for a target package id.
     *
     * @param componentIdentifier packageIdentifier
     * @return the artifact directory path for target package.
     * @throws PackageLoadingException if creating the directory fails
     */
    public Path resolveArtifactDirectoryPath(@NonNull ComponentIdentifier componentIdentifier)
            throws PackageLoadingException {
        try {
            return nucleusPaths.artifactPath(componentIdentifier);
        } catch (IOException e) {
            throw new PackageLoadingException("Unable to create artifact path", e);
        }
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
        return nucleusPaths.recipePath()
                .resolve(String.format(RECIPE_FILE_NAME_FORMAT, packageName, packageVersion.getValue()));
    }

    /**
     * Get the total size of files in the package store by recursively walking the package store directory. Provides an
     * estimate of the package store's disk usage.
     *
     * @return total length of files in bytes
     * @throws PackageLoadingException if unable to access the package store directory
     */
    public long getContentSize() throws PackageLoadingException {
        try {
            return Files.walk(nucleusPaths.componentStorePath()).map(Path::toFile).filter(File::isFile)
                    .mapToLong(File::length).sum();
        } catch (IOException e) {
            throw new PackageLoadingException("Failed to access package store", e);
        }
    }

    /**
     * Get remaining usable bytes for the package store.
     *
     * @return usable bytes
     * @throws PackageLoadingException if I/O error occurred
     */
    public long getUsableSpace() throws PackageLoadingException {
        try {
            return Files.getFileStore(nucleusPaths.componentStorePath()).getUsableSpace();
        } catch (IOException e) {
            throw new PackageLoadingException(
                    "Failed to get usable disk space for directory: " + nucleusPaths.componentStorePath(), e);
        }
    }

    private static String parsePackageNameFromFileName(String filename) {
        // TODO: [P41215992]: Validate recipe filename before extracting name and version from it

        // MonitoringService-1.0.0.yaml
        String[] packageNameAndVersionParts = filename.split(FileSuffix.YAML_SUFFIX)[0].split("-");

        return String.join("-", Arrays.copyOf(packageNameAndVersionParts, packageNameAndVersionParts.length - 1));
    }

    private static Semver parseVersionFromFileName(String filename) throws PackageLoadingException {
        // TODO: [P41215992]: Validate recipe filename before extracting name and version from it

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

    /**
     * Saves recipe metadata to file. Overrides if the target file exists.
     *
     * @param componentIdentifier component id
     * @param recipeMetadata      metadata for the recipe
     * @throws PackageLoadingException when failed write recipe metadata to file system.
     */
    public void saveRecipeMetadata(ComponentIdentifier componentIdentifier, RecipeMetadata recipeMetadata)
            throws PackageLoadingException {
        File metadataFile = resolveRecipeMetadataFile(componentIdentifier);

        try {
            SerializerFactory.getFailSafeJsonObjectMapper().writeValue(metadataFile, recipeMetadata);
        } catch (IOException e) {
            logger.atError().cause(e).kv(RECIPE_METADATA_FILE_PATH_LOG_KEY, metadataFile.getAbsolutePath())
                    .log("Failed to write recipe metadata file");

            throw new PackageLoadingException(
                    String.format("Failed to write recipe metadata to file: '%s'.", metadataFile.getAbsolutePath()), e);
        }
    }

    /**
     * Reads component recipe metadata file.
     *
     * @param componentIdentifier component id
     * @throws PackageLoadingException if failed to read recipe metadata from file system or failed to parse the file.
     */
    public RecipeMetadata getRecipeMetadata(ComponentIdentifier componentIdentifier) throws PackageLoadingException {
        File metadataFile = resolveRecipeMetadataFile(componentIdentifier);

        if (!metadataFile.exists() || !metadataFile.isFile()) {
            // log error because this is not expected to happen in any normal case
            logger.atError().kv(RECIPE_METADATA_FILE_PATH_LOG_KEY, metadataFile.getAbsolutePath())
                    .log("Failed to get recipe metadata because the file doesn't not exit or it is a folder");

            throw new PackageLoadingException(String.format(
                    "Failed to get recipe metadata because the file doesn't not exit or it is a folder. "
                            + RECIPE_METADATA_FILE_PATH_LOG_KEY + ": '%s'.", metadataFile.getAbsolutePath()));
        }

        try {
            return SerializerFactory.getFailSafeJsonObjectMapper().readValue(metadataFile, RecipeMetadata.class);

            // exception handling is intentionally heavy so that to deal with file corruption
            // TODO review note: I struggled btw having the below or removing it. Tried to remove it and feel a single
            // catch on IOException and saying file is corrupted is a little thin.
            // Furthermore, we should do the similar to recipe file! That's a lot more important.
        } catch (JsonParseException e) {
            // log error because this is not expected to happen in any normal case
            logger.atError().cause(e).kv(RECIPE_METADATA_FILE_PATH_LOG_KEY, metadataFile.getAbsolutePath())
                    .log("Failed to get recipe metadata because the recipe metadata file should be a json "
                                 + "but is corrupted");

            throw new PackageLoadingException(String.format(
                    "Failed to get recipe metadata because the recipe metadata file should be a json but is corrupted. "
                            + RECIPE_METADATA_FILE_PATH_LOG_KEY + ": '%s'.", metadataFile.getAbsolutePath()), e);

        } catch (JsonMappingException e) {
            // log error because this is not expected to happen in any normal case
            logger.atError().cause(e).kv(RECIPE_METADATA_FILE_PATH_LOG_KEY, metadataFile.getAbsolutePath())
                    .log("Failed to get recipe metadata because the recipe metadata file json has wrong structure");


            throw new PackageLoadingException(String.format(
                    "Failed to get recipe metadata because the recipe metadata file json has wrong structure. "
                            + RECIPE_METADATA_FILE_PATH_LOG_KEY + ": '%s'.", metadataFile.getAbsolutePath()), e);

        } catch (IOException e) {
            // log error because this is not expected to happen in any normal case
            logger.atError().cause(e).kv("RecipeMetadataFilePath", metadataFile.getAbsolutePath())
                    .log("Failed to get recipe metadata because the file can't be read due to low-level I/O error");


            throw new PackageLoadingException(String.format(
                    "Failed to get recipe metadata because the file can't be read due to low-level I/O error."
                            + " RecipeMetadataFilePath: '%s'.", metadataFile.getAbsolutePath()), e);
        }
    }

    private File resolveRecipeMetadataFile(ComponentIdentifier componentIdentifier) {
        String hashOfComponentName = null;
        try {
            // calculate a hash for component name so that it is safe to be in a file name cross platform
            // padding is removed to avoid confusion
            hashOfComponentName = Digest.calculateWithUrlEncoderNoPadding(componentIdentifier.getName());
        } catch (NoSuchAlgorithmException e) {
            //TODO
        }

        // @ is used as delimiter between component name hash and semver
        // .metadata is to indicate it contains metadata info
        // .json at the end is to indicate the file cotnent type is a json
        String recipeMetaDataFileName =
                String.format("%s@%s.metadata.json", hashOfComponentName, componentIdentifier.getVersion().getValue());

        return nucleusPaths.recipePath().resolve(recipeMetaDataFileName).toFile();
    }
}
