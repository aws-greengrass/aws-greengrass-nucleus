package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Package;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
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
        Map<String, Package> allPackages = new HashMap<>();
        Queue<Package> packageQueue = new LinkedList<>();
        packageQueue.offer(rootPackage);

        while (!packageQueue.isEmpty()) {
            Package pkg = packageQueue.poll();
            allPackages.put(pkg.getPackageName() + "-" + pkg.getPackageVersion(), pkg);
            for (Package.Dependency dependency : pkg.getDependencies()) {
                Map<String, Package> depPackages = pkg.getDependencyPackageMap();
                for (Package p : depPackages.values()) {
                    packageQueue.offer(p);
                }
            }
        }

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
