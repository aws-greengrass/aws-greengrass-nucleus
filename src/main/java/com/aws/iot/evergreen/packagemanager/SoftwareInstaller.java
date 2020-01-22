package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Recipe;
import java.nio.file.Path;

public class SoftwareInstaller {

    private final PackageDatabaseAccessor databaseAccessor;

    private final Path workingDirectory;

    public SoftwareInstaller(PackageDatabaseAccessor databaseAccessor, Path workingDirectory) {
        this.databaseAccessor = databaseAccessor;
        this.workingDirectory = workingDirectory;
    }

    public void copyInstall(Recipe rootRecipe) {
        PackageEntry rootPackage = databaseAccessor.get(rootRecipe.getPackageName(), rootRecipe.getPackageVersion());
        // copy artifacts to working directory rootPackage.getArtifactUrls()

        // repeat for dependencies
    }

}
