package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.PackageEntry;
import com.aws.iot.evergreen.packagemanager.model.Package;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedList;
import java.util.Queue;

public class SoftwareInstaller {

    private final PackageDatabaseAccessor databaseAccessor;

    private final Path workingDirectory;

    public SoftwareInstaller(PackageDatabaseAccessor databaseAccessor, Path workingDirectory) {
        this.databaseAccessor = databaseAccessor;
        this.workingDirectory = workingDirectory;
    }

    public void copyInstall(Package rootPackage) {
        Path serviceWorkingDirectory = createServiceWorkingDirectory(rootPackage.getServiceName());

        Queue<Package> packageQueue = new LinkedList<>();
        packageQueue.offer(rootPackage);

        while(!packageQueue.isEmpty()) {
            Package pkg = packageQueue.poll();
            PackageEntry packageEntry = databaseAccessor.findPackage(pkg.getPackageName(), pkg.getPackageVersion());
            for (String path : packageEntry.getArtifactPaths()) {
                copyCachedArtifactsToWorkingDirectory(serviceWorkingDirectory, path);
            }
            for (Package dependency : pkg.getDependencyPackageMap().values()) {
                packageQueue.offer(dependency);
            }
        }
    }

    private void copyCachedArtifactsToWorkingDirectory(Path workingDirectory, String artifactCachedLoc) {
        Path artifactPath = Paths.get(artifactCachedLoc);
        Path copyDest = workingDirectory.resolve(artifactPath.getFileName());

        try {
            Files.copy(artifactPath, copyDest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to copy artifact to working directory", e);
        }
    }

    private Path createServiceWorkingDirectory(String serviceName) {
        Path serviceDirectoryPath = workingDirectory.resolve(serviceName);
        File serviceDirectory = new File(serviceDirectoryPath.toString());
        if (!serviceDirectory.exists()){
            serviceDirectory.mkdir();
        }

        return serviceDirectoryPath;
    }

}
