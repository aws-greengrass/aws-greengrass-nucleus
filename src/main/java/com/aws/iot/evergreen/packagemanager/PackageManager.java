package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.Package;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PackageManager {

    private static final Path CACHE_DIRECTORY = Paths.get(System.getProperty("user.dir")).resolve("artifact_cache");
    private static final Path WORKING_DIRECTORY = Paths.get(System.getProperty("user.dir")).resolve("working_directory");

    // For POC, use a concurrent map acts as service registry.
    private final ConcurrentHashMap<String, Package> serviceRegistryMap = new ConcurrentHashMap<>();

    private final PackageLoader packageLoader;

    private final ArtifactCache artifactCache;

    private final SoftwareInstaller softwareInstaller;

    private int downloadTrialCount = 0;

    public PackageManager() {
        PackageDatabaseAccessor packageDatabaseAccessor = new PackageDatabaseAccessor();
        this.packageLoader = new PackageLoader(packageDatabaseAccessor);
        this.artifactCache  = new ArtifactCache(packageDatabaseAccessor, CACHE_DIRECTORY);
        this.softwareInstaller = new SoftwareInstaller(packageDatabaseAccessor, WORKING_DIRECTORY);
    }

    private Package loadPackage(String packageIdentifier) {
        String[] ids = packageIdentifier.split("-");
        if (ids.length != 2) {
            throw new RuntimeException("Failed to recognize package name/version");
        }
        return packageLoader.loadPackage(ids[0], ids[1]);
    }

    public Map<String, Package> loadPackages(Set<String> targetPackageIdentifiers) {
        return targetPackageIdentifiers.stream().collect(Collectors.toMap(Function.identity(), this::loadPackage));
    }

    public Set<Package> getPendingDownloadPackages(Set<String> targetPackageIdentifiers) {
        return Collections.emptySet();
    }

    /*
     given packages, download its  artifacts
     the downloading process should be asynchronous with boolean return type
     to indicate whether it's finished
     */
    public Future<Boolean> downloadPackages(Set<Package> pendingDownloadPackage) {
        System.out.println(String.format("download %d trial", downloadTrialCount+1));
        return Executors.newSingleThreadExecutor().submit(() -> {
            if (++ downloadTrialCount < 3) {
                System.out.println("downloading ...");
                Thread.sleep(60000);
                return true;
            } else {
                System.out.println("download succeed!");
                return true;
            }
        });
    }


    public void installService(String serviceName) {
        Package rootPackage = serviceRegistryMap.get(serviceName);
        // install software
        softwareInstaller.copyInstall(rootPackage);
    }

}
