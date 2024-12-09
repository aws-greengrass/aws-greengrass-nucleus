/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.exceptions.HashingAlgorithmUnavailableException;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.constants.FileSuffix;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.NonNull;
import org.apache.commons.io.FileUtils;
import software.amazon.awssdk.arns.Arn;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import javax.inject.Inject;

public class ComponentStore {

    public static final String RECIPE_DIRECTORY = "recipes";
    public static final String ARTIFACT_DIRECTORY = "artifacts";
    public static final String ARTIFACTS_DECOMPRESSED_DIRECTORY = "artifacts-unarchived";
    public static final String RECIPE_FILE_NAME_FORMAT = "%s-%s.yaml";

    private static final Logger logger = LogManager.getLogger(ComponentStore.class);
    private static final String LOG_KEY_RECIPE_METADATA_FILE_PATH = "RecipeMetadataFilePath";
    private static final String LOG_METADATA_INVALID = "Ignoring the local recipe metadata file and proceeding with "
            + "dependency resolution";
    private static final String RECIPE_SUFFIX = ".recipe";

    private final NucleusPaths nucleusPaths;
    private final PlatformResolver platformResolver;
    private final RecipeLoader recipeLoader;

    /**
     * Constructor. It will initialize recipe, artifact and artifact decompressed directory.
     *
     * @param nucleusPaths     path library
     * @param platformResolver platform resolver
     * @param recipeLoader     recipe loader
     */
    @Inject
    public ComponentStore(NucleusPaths nucleusPaths, PlatformResolver platformResolver, RecipeLoader recipeLoader) {
        this.nucleusPaths = nucleusPaths;
        this.platformResolver = platformResolver;
        this.recipeLoader = recipeLoader;
    }

    /**
     * Save the given component recipe object into component store on the disk.
     *
     * <p>If the target recipe file exist, and its content is the same as the content to be written, it skip the
     * file write operation.
     * If content is different or the target recipe file does not exist, it will write to the file using YAML
     * serializer.
     * </p>
     *
     * @see com.amazon.aws.iot.greengrass.component.common.SerializerFactory#getRecipeSerializer
     * @param componentRecipe raw component recipe
     * @return persisted recipe content in component store on the disk.
     * @throws PackageLoadingException if fails to write the package recipe to disk.
     */
    String saveComponentRecipe(@NonNull com.amazon.aws.iot.greengrass.component.common.ComponentRecipe componentRecipe)
            throws PackageLoadingException {
        ComponentIdentifier componentIdentifier =
                new ComponentIdentifier(componentRecipe.getComponentName(), componentRecipe.getComponentVersion());

        try {
            String recipeContent =
                    com.amazon.aws.iot.greengrass.component.common.SerializerFactory.getRecipeSerializer()
                            .writeValueAsString(componentRecipe);

            Optional<String> componentRecipeContent = findComponentRecipeContent(componentIdentifier);
            if (componentRecipeContent.isPresent() && componentRecipeContent.get().equals(recipeContent)) {
                // same content and no need to write again
                return recipeContent;
            }

            FileUtils.writeStringToFile(resolveRecipePath(componentIdentifier).toFile(), recipeContent);

            return recipeContent;
        } catch (IOException e) {
            // TODO: [P41215929]: Better logging and exception messages in component store
            throw new PackageLoadingException("Failed to save package recipe", e)
                    .withErrorContext(e, DeploymentErrorCode.IO_WRITE_ERROR);
        }
    }

    /**
     * Creates or updates a package recipe in the package store on the disk.
     *
     * @param componentId   the id for the component
     * @param recipeContent recipe content to save
     * @throws PackageLoadingException if fails to write the package recipe to disk.
     */
    public void savePackageRecipe(@NonNull ComponentIdentifier componentId, String recipeContent)
            throws PackageLoadingException {
        try {
            Path recipePath = resolveRecipePath(componentId);
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
     * @throws PackageLoadingException if unable to load recipe from disk
     */
    public boolean validateComponentRecipeDigest(@NonNull ComponentIdentifier componentIdentifier,
                                                 String expectedDigest) throws PackageLoadingException {
        Optional<String> recipeContent = findComponentRecipeContent(componentIdentifier);
        if (!recipeContent.isPresent()) {
            throw new PackageLoadingException("Recipe not found for component " + componentIdentifier.getName());
        }
        String recipeContentStr = recipeContent.get();
        if (Utils.isEmpty(recipeContentStr)) {
            throw new PackageLoadingException(
                    String.format("Found empty recipe for component %s. File was likely corrupted",
                            componentIdentifier.getName()));
        }
        try {
            String digest = Digest.calculate(recipeContentStr);
            logger.atTrace("plugin-load").log("Digest from store: " + Coerce.toString(expectedDigest));
            logger.atTrace("plugin-load").log("Digest from recipe: " + Coerce.toString(digest));
            return Digest.isEqual(digest, expectedDigest);
        } catch (NoSuchAlgorithmException e) {
            throw new PackageLoadingException("Cannot validate digest for recipe due to missing hash algorithm", e);
        }
    }

    Optional<String> findComponentRecipeContent(@NonNull ComponentIdentifier componentId)
            throws PackageLoadingException {
        Path recipePath = resolveRecipePath(componentId);

        logger.atDebug().setEventType("finding-package-recipe").addKeyValue("packageRecipePath", recipePath).log();

        if (!Files.exists(recipePath) || !Files.isRegularFile(recipePath)) {
            return Optional.empty();
        }

        try {
            return Optional.of(new String(Files.readAllBytes(recipePath), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PackageLoadingException(
                    String.format("Failed to read package recipe from disk with path: `%s`", recipePath),
                    e).withErrorContext(e, DeploymentErrorCode.IO_READ_ERROR);
        }
    }

    /**
     * Get the package recipe from package store on the disk.
     *
     * @param pkgId package identifier
     * @return retrieved package recipe.
     * @throws PackageLoadingException if fails to find the target package recipe or fails to parse the recipe file.
     */
    public ComponentRecipe getPackageRecipe(@NonNull ComponentIdentifier pkgId) throws PackageLoadingException {
        Optional<ComponentRecipe> optionalPackage = findPackageRecipe(pkgId);

        if (!optionalPackage.isPresent()) {
            // TODO: [P41215929]: Better logging and exception messages in component store
            throw new PackageLoadingException(String.format(
                    "Failed to find usable recipe for current platform: %s, for package: '%s' in the "
                            + "local package store", platformResolver.getCurrentPlatform(), pkgId),
                    DeploymentErrorCode.LOCAL_RECIPE_NOT_FOUND);
        }

        return optionalPackage.get();
    }

    /**
     * Delete the component recipe, artifacts, and decompressed files from disk.
     *
     * @param compId component identifier
     * @throws PackageLoadingException if deletion of the component failed
     */
    void deleteComponent(@NonNull ComponentIdentifier compId,
                         @NonNull ArtifactDownloaderFactory artifactDownloaderFactory)
            throws PackageLoadingException, InvalidArtifactUriException {
        logger.atDebug("delete-component-start").kv("componentIdentifier", compId).log();
        IOException exception = null;
        // issues #1111
        // delete docker image before removing the recipe file
        Optional<String> componentRecipeContent = findComponentRecipeContent(compId);
        if (componentRecipeContent.isPresent()) {
            // get recipe, all recipes are saved as yml files
            ComponentRecipe recipe = getPackageRecipe(compId);
            Path packageArtifactDirectory = resolveArtifactDirectoryPath(compId);
            for (ComponentArtifact artifact : recipe.getArtifacts()) {
                ArtifactDownloader downloader = artifactDownloaderFactory
                        .getArtifactDownloader(compId, artifact, packageArtifactDirectory);
                try {
                    downloader.cleanup();
                } catch (IOException e) {
                    if (exception == null) {
                        exception = e;
                    } else {
                        exception.addSuppressed(e);
                    }
                }
            }
        }
        // delete recipe
        try {
            Path recipePath = resolveRecipePath(compId);
            Files.deleteIfExists(recipePath);
        } catch (IOException e) {
            if (exception == null) {
                exception = e;
            } else {
                exception.addSuppressed(e);
            }
        }
        // delete recipeMetadata
        try {
            Files.deleteIfExists(resolveRecipeMetadataFile(compId).toPath());
        } catch (IOException e) {
            if (exception == null) {
                exception = e;
            } else {
                exception.addSuppressed(e);
            }
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

    Optional<ComponentIdentifier> findBestMatchAvailableComponent(@NonNull String componentName,
                                                                  @NonNull Requirement requirement)
            throws PackageLoadingException {
        List<ComponentIdentifier> componentIdentifierList = listAvailableComponent(componentName, requirement);

        if (componentIdentifierList.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.of(componentIdentifierList.get(0));
        }
    }

    /**
     * List available component (versions) that satisfies the requirement in descending order.
     * @param componentName target component's name
     * @param requirement   semver requirement
     * @return component id list contains all satisfied version, in descending order
     * @throws PackageLoadingException  when fails to read recipe directory or parse recipe file name
     */
    List<ComponentIdentifier> listAvailableComponent(@NonNull String componentName, @NonNull Requirement requirement)
            throws PackageLoadingException {
        String componentNameHash = getHashOfComponentName(componentName);

        // target file name: {hash}@{semver}.recipe.yaml
        File[] recipeFilesOfAllVersions = nucleusPaths.recipePath().toFile().listFiles(
                (dir, name) -> name.endsWith(RECIPE_SUFFIX + FileSuffix.YAML_SUFFIX) && name
                        .startsWith(componentNameHash));

        if (recipeFilesOfAllVersions == null || recipeFilesOfAllVersions.length == 0) {
            return new ArrayList<>();
        }

        List<ComponentIdentifier> satisfyingComponentIds = new ArrayList<>();

        // for each loop is used, instead of lambda expression, because parseVersionFromRecipeFileName throws checked
        // exception and lambda doesn't support throwing checked exception
        for (File recipeFile : recipeFilesOfAllVersions) {
            Semver version = parseVersionFromRecipeFileName(recipeFile.getName());

            if (requirement.isSatisfiedBy(version)) {
                satisfyingComponentIds.add(new ComponentIdentifier(componentName, version));
            }
        }

        // Sort in descending order
        satisfyingComponentIds.sort(Collections.reverseOrder());
        return satisfyingComponentIds;
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
            throw new PackageLoadingException("Unable to create artifact path", e)
                    .withErrorContext(e, DeploymentErrorCode.IO_WRITE_ERROR);
        }
    }

    /**
     * Returns the artifact file name.
     *
     * @param componentIdentifier packageIdentifier
     * @param artifactDownloaderFactory artifact downloader factory
     * @return the unarchive artifact directory path for target package.
     * @throws PackageLoadingException if creating the directory fails
     */
    public List<File> getArtifactFiles(@NonNull ComponentIdentifier componentIdentifier,
                                       @NonNull ArtifactDownloaderFactory artifactDownloaderFactory)
            throws PackageLoadingException {
        Optional<String> componentRecipeContent = findComponentRecipeContent(componentIdentifier);
        if (!componentRecipeContent.isPresent()) {
            return Collections.emptyList();
        }

        ComponentRecipe recipe = getPackageRecipe(componentIdentifier);
        Path packageArtifactDirectory = resolveArtifactDirectoryPath(componentIdentifier);
        return recipe.getArtifacts().stream().map(artifact -> {
            try {
                return artifactDownloaderFactory
                        .getArtifactDownloader(componentIdentifier, artifact, packageArtifactDirectory)
                        .getArtifactFile();
            } catch (PackageLoadingException | InvalidArtifactUriException e) {
                logger.atDebug().setCause(e).kv("comp-id", componentRecipeContent)
                        .log("Could not get artifact file");
                return null;
            }
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    /**
     * Resolve the recipe file path for a target package id.
     *
     * @param componentIdentifier packageIdentifier
     * @return the recipe file path for target package.
     * @throws PackageLoadingException if unable to resolve recipe file path
     */
    public Path resolveRecipePath(@NonNull ComponentIdentifier componentIdentifier) throws PackageLoadingException {
        // .recipe is to indicate it is the recipe
        // .yaml at the end is to indicate the file content type is a yaml
        String recipeFileName = String.format("%s.recipe.yaml", getFilenamePrefixFromComponentId(componentIdentifier));

        return nucleusPaths.recipePath().resolve(recipeFileName);
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
            try (LongStream lengths = Files.walk(nucleusPaths.componentStorePath()).map(Path::toFile)
                    .filter(File::isFile).mapToLong(File::length)) {
                return lengths.sum();
            }
        } catch (IOException e) {
            throw new PackageLoadingException("Failed to access package store", e)
                    .withErrorContext(e, DeploymentErrorCode.IO_FILE_ATTRIBUTE_ERROR);
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

    private static Semver parseVersionFromRecipeFileName(String recipeFilename) throws PackageLoadingException {
        // TODO: [P41215992]: Validate recipe filename before extracting name and version from it

        // {hash}}@{semver}.recipe.yaml
        String versionStr = recipeFilename.split(RECIPE_SUFFIX + FileSuffix.YAML_SUFFIX)[0].split("@")[1];

        try {
            return new Semver(versionStr);
        } catch (SemverException e) {
            throw new PackageLoadingException(
                    String.format("Component recipe file name: '%s' is corrupted", recipeFilename), e,
                    DeploymentErrorCode.LOCAL_RECIPE_CORRUPTED);
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
            logger.atError().cause(e).kv(LOG_KEY_RECIPE_METADATA_FILE_PATH, metadataFile.getAbsolutePath())
                    .log("Failed to write recipe metadata file");

            throw new PackageLoadingException(
                    String.format("Failed to write recipe metadata to file: '%s'", metadataFile.getAbsolutePath()),
                    e).withErrorContext(e, DeploymentErrorCode.IO_WRITE_ERROR);
        }
    }

    /**
     * Get component version arn stored in local metadata and check if its region matches the expected region.
     *
     * @param localCandidate component to be checked
     * @param region expected region
     * @return true if region matches; false if region does not match or anything goes wrong
     */
    public boolean componentMetadataRegionCheck(ComponentIdentifier localCandidate, String region) {
        File metadataFile;
        try {
            metadataFile = resolveRecipeMetadataFile(localCandidate);
        } catch (PackageLoadingException e) {
            // Hashing algorithm does not exist, which should never happen
            return true;
        }

        try {
            RecipeMetadata recipeMetadata = getRecipeMetadata(metadataFile);
            Arn arn = Arn.fromString(recipeMetadata.getComponentVersionArn());
            Optional<String> arnRegion = arn.region();
            if (arnRegion.isPresent()) {
                if (region.equals(arnRegion.get())) {
                    // region matches
                    return true;
                } else {
                    logger.atWarn().kv("componentName", localCandidate.toString())
                            .kv("expectedRegion", region).kv("foundRegion", arnRegion.get())
                            .kv("metadataPath", metadataFile.getAbsolutePath())
                            .log("Component version arn in recipe metadata contains a different region from "
                                    + "nucleus config. " + LOG_METADATA_INVALID);
                    return false;
                }
            } else {
                logger.atWarn().kv("componentName", localCandidate.toString())
                        .kv("metadataPath", metadataFile.getAbsolutePath())
                        .log("Invalid region value for component version arn in recipe metadata. "
                                + LOG_METADATA_INVALID);
                return false;
            }
        } catch (PackageLoadingException e) {
            if (e.getErrorCodes().contains(DeploymentErrorCode.LOCAL_RECIPE_METADATA_NOT_FOUND)) {
                // if file does not exist, then it is likely a locally installed component
                // if not, deployment will fail when downloading artifact from cloud
                return true;
            }
            // not logging the file path since it's already logged previously in getRecipeMetadata
            logger.atWarn().setCause(e).log("Failed to read metadata. " + LOG_METADATA_INVALID);
        } catch (IllegalArgumentException e) {
            // Failed to parse the Arn string
            logger.atWarn().kv("componentName", localCandidate.toString()).setCause(e)
                    .kv("metadataPath", metadataFile.getAbsolutePath())
                    .log("Failed to parse the component version arn in recipe metadata. " + LOG_METADATA_INVALID);
        }
        return false;
    }

    /**
     * Reads component recipe metadata file.
     *
     * @param componentIdentifier component id
     * @throws PackageLoadingException if failed to read recipe metadata from file system or failed to parse the file.
     */
    public RecipeMetadata getRecipeMetadata(ComponentIdentifier componentIdentifier) throws PackageLoadingException {
        File metadataFile = resolveRecipeMetadataFile(componentIdentifier);
        return getRecipeMetadata(metadataFile);
    }

    private RecipeMetadata getRecipeMetadata(File metadataFile) throws PackageLoadingException {
        if (!metadataFile.exists()) {
            // this may happen if it's a locally installed component and has no metadata
            logger.atDebug().kv(LOG_KEY_RECIPE_METADATA_FILE_PATH, metadataFile.getAbsolutePath())
                    .log("Recipe metadata file doesn't exist");

            throw new PackageLoadingException(String.format(
                    "Recipe metadata file doesn't exist. RecipeMetadataFilePath: '%s'", metadataFile.getAbsolutePath()),
                    DeploymentErrorCode.LOCAL_RECIPE_METADATA_NOT_FOUND);
        }

        if (!metadataFile.isFile()) {
            logger.atError().kv(LOG_KEY_RECIPE_METADATA_FILE_PATH, metadataFile.getAbsolutePath())
                    .log("Failed to get recipe metadata because the path resolved to a folder");

            throw new PackageLoadingException(String.format(
                    "Failed to get recipe metadata because the path resolved to a folder. "
                            + "RecipeMetadataFilePath: '%s'", metadataFile.getAbsolutePath()),
                    DeploymentErrorCode.LOCAL_RECIPE_METADATA_NOT_FOUND);
        }


        try {
            return SerializerFactory.getFailSafeJsonObjectMapper().readValue(metadataFile, RecipeMetadata.class);
        } catch (JsonProcessingException e) {
            // log error because this is not expected to happen in any normal case
            logger.atError().cause(e).kv(LOG_KEY_RECIPE_METADATA_FILE_PATH, metadataFile.getAbsolutePath())
                    .log("Recipe metadata is not valid JSON");

            throw new PackageLoadingException(String.format(
                    "Recipe metadata is not valid JSON: '%s'", metadataFile.getAbsolutePath()), e)
                    .withErrorContext(e, DeploymentErrorCode.RECIPE_METADATA_PARSE_ERROR);
        } catch (IOException e) {
            // log error because this is not expected to happen in any normal case
            logger.atError().cause(e).kv(LOG_KEY_RECIPE_METADATA_FILE_PATH, metadataFile.getAbsolutePath())
                    .log("I/O error while reading recipe metadata");

            throw new PackageLoadingException(String.format(
                    "I/O error while reading recipe metadata. RecipeMetadataFilePath: '%s'",
                    metadataFile.getAbsolutePath()), e)
                    .withErrorContext(e, DeploymentErrorCode.IO_READ_ERROR);
        }
    }

    private File resolveRecipeMetadataFile(ComponentIdentifier componentIdentifier) throws PackageLoadingException {
        // .metadata is to indicate it is the metadata
        // .json at the end is to indicate the file content type is a json
        String recipeMetaDataFileName =
                String.format("%s.metadata.json", getFilenamePrefixFromComponentId(componentIdentifier));

        return nucleusPaths.recipePath().resolve(recipeMetaDataFileName).toFile();
    }

    /**
     * Get the file name prefix that is safe for cross-platform file systems for persistence.
     *
     * @param componentIdentifier componentIdentifier
     * @return a file name prefix for a component version that is safe for cross-platform file systems
     */
    private String getFilenamePrefixFromComponentId(ComponentIdentifier componentIdentifier)
            throws PackageLoadingException {
        // @ is used as delimiter between component name hash and semver
        // because it is cross-platform file system safe and also meaningful
        return String.format("%s@%s", getHashOfComponentName(componentIdentifier.getName()),
                componentIdentifier.getVersion().getValue());
    }

    private String getHashOfComponentName(String componentName) throws HashingAlgorithmUnavailableException {
        try {
            // calculate a hash for component name so that it is safe to be in a file name cross platform
            // padding is removed to avoid confusion
            return Digest.calculateWithUrlEncoderNoPadding(componentName);
        } catch (NoSuchAlgorithmException e) {
            // This should never happen as SHA-256 is mandatory for every default JVM provider
            throw new HashingAlgorithmUnavailableException(
                    "Failed to compute filename because desired hashing algorithm is not available", e);
        }
    }
}
