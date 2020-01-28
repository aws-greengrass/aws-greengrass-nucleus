package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Package;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.*;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

public class ArtifactCache {

    private final PackageDatabaseAccessor databaseAccessor;

    private final Path cacheFolder;

    public ArtifactCache(PackageDatabaseAccessor databaseAccessor, Path cacheFolder) {
        this.databaseAccessor = databaseAccessor;
        this.cacheFolder = cacheFolder;
    }

    public void cacheArtifact(Package rootPackage) {
        Map<String, Package> allPackages = rootPackage.getDependencyPackageMap();
        allPackages.put(rootPackage.getPackageName() + "-" + rootPackage.getPackageVersion(), rootPackage);

        for (String packageName : allPackages.keySet()) {
            Package curPackage = allPackages.get(packageName);
            Path dest_package_root_path = Paths.get(cacheFolder.toString(), packageName);
            File dest_package_root_dir = new File(dest_package_root_path.toString());
            if (!dest_package_root_dir.exists()){
                dest_package_root_dir.mkdir();
            } else {
                // Package/version already exists, move to next
                continue;
            }

            Map<String, Package> curPackageDependencies = curPackage.getDependencyPackageMap();
            if(curPackageDependencies.size() > 0) {
                // Needs to remove duplicates to save on processing time
                allPackages.putAll(curPackageDependencies);
            }

            Set<String> artifactUrls = curPackage.getArtifactUrls();
            List<String> localArtifactPaths = new ArrayList<>();
            for (String artifactUrl : artifactUrls) {
                // replace this with real implementation
                //ArtifactProvider artifactProvider = new LocalArtifactProvider();

                //ByteArrayOutputStream out = artifactProvider.loadArtifact(artifactUrl);

                //cache bytes to local cache directory, return local URL
                //add to the list
                Path source = Paths.get(artifactUrl);
                Path dest = Paths.get(dest_package_root_path.toString(), packageName);

                try {
                    Files.copy(source, dest, REPLACE_EXISTING);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to copy artifact " + artifactUrl + " from package " + packageName);
                }

                localArtifactPaths.add(dest.toString());
            }

            // Update package database with local URL
            PackageEntry packageEntry = databaseAccessor.findPackage(curPackage.getPackageName(), curPackage.getPackageVersion());
            databaseAccessor.updatePackageArtifacts(packageEntry, localArtifactPaths);
        }
    }

}
