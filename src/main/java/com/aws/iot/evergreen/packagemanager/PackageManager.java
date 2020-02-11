package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.Package;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

public class PackageManager {

    private static final Path CACHE_DIRECTORY = Paths.get(System.getProperty("user.dir")).resolve("artifact_cache");
    private static final Path WORKING_DIRECTORY = Paths.get(System.getProperty("user.dir")).resolve("working_directory");

    // For POC, use a concurrent map acts as service registry.
    private final ConcurrentHashMap<String, Package> serviceRegistryMap = new ConcurrentHashMap<>();

    private final PackageLoader packageLoader;

    private final ArtifactCache artifactCache;

    private final SoftwareInstaller softwareInstaller;

    public PackageManager() {
        PackageDatabaseAccessor packageDatabaseAccessor = new PackageDatabaseAccessor();
        this.packageLoader = new PackageLoader(packageDatabaseAccessor);
        this.artifactCache  = new ArtifactCache(packageDatabaseAccessor, CACHE_DIRECTORY);
        this.softwareInstaller = new SoftwareInstaller(packageDatabaseAccessor, WORKING_DIRECTORY);
    }

    public Package loadPackage(String packageFolder) {
        Package rootPackage = packageLoader.loadPackage(Paths.get(packageFolder));
        // cache artifact
        artifactCache.cacheArtifact(rootPackage);
        //root recipe should contain service name
        serviceRegistryMap.put(rootPackage.getServiceName(), rootPackage);

        return rootPackage;
    }

    public Package loadPackage(String targetPackageName, String targetPackageVersion) {
        return null;
    }

    /*
     given target package, download it and its dependencies artifacts
     the downloading process should be asynchronous with boolean return type
     to indicate whether it's finished
     */
    public boolean downloadArtifacts(Package targetPackage) {
        // for simplicity, implement in synchronous manner
        artifactCache.cacheArtifact(targetPackage);
        return true;
    }

    public void installService(String serviceName) {
        Package rootPackage = serviceRegistryMap.get(serviceName);
        // install software
        softwareInstaller.copyInstall(rootPackage);
    }

}
