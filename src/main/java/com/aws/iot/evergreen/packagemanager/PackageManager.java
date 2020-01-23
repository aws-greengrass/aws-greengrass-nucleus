package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.model.Package;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;

public class PackageManager {

    // For POC, use a concurrent map acts as service registry.
    private final ConcurrentHashMap<String, Package> serviceRegistryMap = new ConcurrentHashMap<>();

    private final PackageLoader packageLoader;

    private final ArtifactCache artifactCache;

    private final SoftwareInstaller softwareInstaller;

    public PackageManager(PackageLoader packageLoader, ArtifactCache artifactCache, SoftwareInstaller softwareInstaller) {
        this.packageLoader = packageLoader;
        this.artifactCache  = artifactCache;
        this.softwareInstaller = softwareInstaller;
    }

    public Package loadPackage(String packageFolder) {
        Package rootPackage = packageLoader.loadPackage(Paths.get(packageFolder));
        // cache artifact
        artifactCache.cacheArtifact(rootPackage);
        //root recipe should contain service name
        serviceRegistryMap.put(rootPackage.getServiceName(), rootPackage);

        return rootPackage;
    }

    public void installService(String serviceName) {
        Package rootPackage = serviceRegistryMap.get(serviceName);
        // install software
        softwareInstaller.copyInstall(rootPackage);
    }

}
