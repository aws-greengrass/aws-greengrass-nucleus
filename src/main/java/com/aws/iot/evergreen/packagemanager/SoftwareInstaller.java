package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.nio.file.Path;

public class SoftwareInstaller {

    private final PackageDatabaseAccessor databaseAccessor;

    private final Path workingDirectory;

    public SoftwareInstaller(PackageDatabaseAccessor databaseAccessor, Path workingDirectory) {
        this.databaseAccessor = databaseAccessor;
        this.workingDirectory = workingDirectory;
    }

    public void copyInstall(Package rootPackage) {
        PackageEntry packageEntry = databaseAccessor.findPackage(rootPackage.getPackageName(), rootPackage.getPackageVersion());
        // copy artifacts to working directory rootPackage.getArtifactUrls()

        // repeat for dependencies
    }

}
