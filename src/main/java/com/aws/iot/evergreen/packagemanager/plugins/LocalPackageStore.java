package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.packagemanager.config.Constants;
import com.aws.iot.evergreen.packagemanager.exceptions.DirectoryCreationFailedForPackageException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnsupportedRecipeFormatException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.util.SerializerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vdurmont.semver4j.Semver;
import com.vdurmont.semver4j.SemverException;
import lombok.AllArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * Local Store Implementation for Evergreen Packages.
 */
@AllArgsConstructor
public class LocalPackageStore implements PackageStore {

    private static final String PACKAGE_RECIPE_CANNOT_BE_NULL = "Package Recipe cannot be null";

    private static final ObjectMapper OBJECT_MAPPER = SerializerFactory.getRecipeSerializer();

    private final Path cacheFolder;

    private static Path getPackageStorageRoot(final String packageName, final Path cacheFolder) {
        return cacheFolder.resolve(packageName);
    }

    private static Path getPackageVersionStorageRoot(final Package curPackageRecipe, final Path cacheFolder) {
        return getPackageVersionStorageRoot(curPackageRecipe.getPackageName(), curPackageRecipe.getVersion().toString(),
                cacheFolder);
    }

    private static Path getPackageVersionStorageRoot(final String packageName, final String packageVersion,
                                                     final Path cacheFolder) {
        return getPackageStorageRoot(packageName, cacheFolder).resolve(packageVersion);
    }

    /**
     * Get package from cache if it exists.
     *
     * @return Optional containing package recipe as a String
     */
    @Override
    public Optional<String> getPackageRecipe(final String packageName, final Semver packageVersion)
            throws PackagingException, IOException {
        Path srcPkgRoot = getPackageVersionStorageRoot(packageName, packageVersion.toString(), cacheFolder);

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

    /**
     * Get package from cache if it exists.
     *
     * @return Optional containing package recipe as a String
     */
    @Override
    public Optional<Package> getPackage(final String packageName, final Semver packageVersion)
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
     */
    @Override
    public List<Semver> getPackageVersionsIfExists(final String packageName) throws UnexpectedPackagingException {
        Path srcPkgRoot = getPackageStorageRoot(packageName, cacheFolder);
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
     */
    @Override
    public Optional<Semver> getLatestPackageVersionIfExists(final String packageName)
            throws UnexpectedPackagingException {
        // Add check for package override
        return Optional.ofNullable(Collections.max(getPackageVersionsIfExists(packageName)));
    }

    /**
     * Cache all artifacts for a given Package.
     */
    @Override
    public void cachePackageArtifacts(final Package curPackageRecipe) throws PackagingException {
        Objects.requireNonNull(curPackageRecipe, PACKAGE_RECIPE_CANNOT_BE_NULL);

        Path destRootPkgPath = getPackageVersionStorageRoot(curPackageRecipe, cacheFolder);

        try {
            if (Files.notExists(destRootPkgPath)) {
                Files.createDirectories(destRootPkgPath);
            }

            // TODO: HACK HACK HACK This should get artifact providers based on key words, not just local
            List<String> artifacts = curPackageRecipe.getArtifacts();
            if (artifacts == null) {
                return;
            }
            for (String artifact : artifacts) {
                LocalArtifactProvider artifactProvider = new LocalArtifactProvider(artifact);
                artifactProvider.downloadArtifactToPath(destRootPkgPath);
            }
        } catch (IOException e) {
            throw new PackagingException("Failed to download artifacts for " + curPackageRecipe.getPackageName(), e);
        }
    }

    /**
     * Cache all artifacts for a given Package.
     */
    @Override
    public void cachePackageRecipeAndArtifacts(final Package curPackage) throws PackagingException {
        Objects.requireNonNull(curPackage, PACKAGE_RECIPE_CANNOT_BE_NULL);
        try {
            String recipeContents = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(curPackage);
            cachePackageRecipeAndArtifacts(curPackage, recipeContents);
        } catch (IOException e) {
            throw new UnsupportedRecipeFormatException(Constants.UNABLE_TO_PARSE_RECIPE_EXCEPTION_MSG, e);
        }
    }

    /**
     * Cache all artifacts for a given Package.
     */
    @Override
    public void cachePackageRecipeAndArtifacts(final Package curPackageRecipe, final String recipeContents)
            throws PackagingException {
        Objects.requireNonNull(curPackageRecipe, PACKAGE_RECIPE_CANNOT_BE_NULL);
        Objects.requireNonNull(recipeContents, "Package Recipe raw string cannot be null");

        Path destRootPkgPath = getPackageVersionStorageRoot(curPackageRecipe, cacheFolder);

        try {
            Files.createDirectories(destRootPkgPath);
        } catch (IOException e) {
            throw new DirectoryCreationFailedForPackageException(
                    "Failed to create folder for " + destRootPkgPath.toString(), e);
        }

        Path pkgRecipePath = destRootPkgPath.resolve(Constants.RECIPE_FILE_NAME);
        try {
            Files.write(pkgRecipePath, recipeContents.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PackagingException("Failed to cache recipe for " + destRootPkgPath.toString(), e);
        }

        cachePackageArtifacts(curPackageRecipe);
    }

    /**
     * Cache all artifacts to a path.
     */
    @Override
    public void copyPackageArtifactsToPath(final Package curPackageRecipe, final Path destPath)
            throws PackagingException {
        Objects.requireNonNull(curPackageRecipe, PACKAGE_RECIPE_CANNOT_BE_NULL);

        Path srcRootPkgPath = getPackageVersionStorageRoot(curPackageRecipe, cacheFolder);
        Path destRootPkgPath = getPackageVersionStorageRoot(curPackageRecipe, destPath);

        copyPackageArtifactsToPath(curPackageRecipe, srcRootPkgPath, destRootPkgPath);
    }

    /**
     * Copy all artifacts to a path.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void copyPackageArtifactsToPath(Package curPackageRecipe, Path srcRootPkgPath, Path destRootPkgPath)
            throws PackagingException {
        if (!Files.exists(srcRootPkgPath) || !Files.isDirectory(srcRootPkgPath)) {
            // TODO: This is may not be the best choice? Maybe throw an exception and die?
            cachePackageArtifacts(curPackageRecipe);
        }

        try {
            Files.createDirectories(destRootPkgPath);
        } catch (IOException e) {
            throw new PackagingException("Failed to create folder for " + destRootPkgPath, e);
        }

        try {
            Files.walk(srcRootPkgPath).forEach(source -> {
                try {
                    if (Files.isDirectory(source) && Files.notExists(source)) {
                        Files.copy(source, destRootPkgPath.resolve(source.getFileName()));
                    } else {
                        Files.copy(source, destRootPkgPath.resolve(source.getFileName()), REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    // TODO: Needs better handling
                    throw new RuntimeException("Failed to copy artifacts for " + curPackageRecipe.getPackageName(), e);
                }
            });
        } catch (IOException | RuntimeException e) {
            // TODO: Needs better handling
            throw new PackagingException("Failed to copy artifacts for " + curPackageRecipe.getPackageName(), e);
        }
    }
}
