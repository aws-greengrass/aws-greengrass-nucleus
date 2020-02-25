package com.aws.iot.evergreen.packagemanager.plugins;

import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.vdurmont.semver4j.Semver;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface PackageStore {
    Optional<String> getPackageRecipeIfExists(final String packageName,
                                                     final Semver packageVersion);

    void cachePackageArtifacts(Package evgPackage) throws PackagingException;

    void cachePackageRecipeAndArtifacts(Package evgPackage, final String recipeContents)
            throws PackagingException;

    void copyPackageArtifactsToPath(Package curPackage, Path destPath) throws PackagingException;

    List<Semver> getPackageVersionsIfExists(final String packageName) throws UnexpectedPackagingException;

    Optional<Semver> getLatestPackageVersionIfExists(final String packageName);
}
