package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ArtifactCache {

    private final PackageDatabaseAccessor databaseAccessor;

    private final Path cacheFolder;

    public ArtifactCache(PackageDatabaseAccessor databaseAccessor, Path cacheFolder) {
        this.databaseAccessor = databaseAccessor;
        this.cacheFolder = cacheFolder;
    }

    public void cacheArtifact(Package rootPackage) {
        Set<String> artifactUrls = rootPackage.getArtifactUrls();

        List<String> localArtifactUrl = new ArrayList<>();
        for (String  artifactUrl : artifactUrls) {
            // replace this with real implementation
            ArtifactProvider artifactProvider = new ArtifactProvider() {
                @Override
                public ByteArrayOutputStream loadArtifact(String artifactUrl) {
                    return null;
                }
            };

            ByteArrayOutputStream out = artifactProvider.loadArtifact(artifactUrl);

            //cache bytes to local cache directory, return local URL
            //add to the list
            localArtifactUrl.add("placeholder");
        }

        // Update package database with local URL
        PackageEntry packageEntry = databaseAccessor.findPackage(rootPackage.getPackageName(), rootPackage.getPackageVersion());
        databaseAccessor.updatePackageArtifacts(packageEntry, localArtifactUrl);

        // Repeat step for dependencies
    }

}
