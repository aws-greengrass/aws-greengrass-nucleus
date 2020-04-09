package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStoreDeprecated;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class GreengrassPackageServiceHelper {
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    //TODO connect to cloud service
    Package downloadPackageRecipe(PackageIdentifier packageIdentifier) throws PackageDownloadException {
        // TODO: to be implemented.
        LocalPackageStoreDeprecated localPackageStore = new LocalPackageStoreDeprecated(LOCAL_CACHE_PATH);
        try {
            return localPackageStore.getPackage(packageIdentifier.getName(), packageIdentifier.getVersion()).get();
        } catch (PackagingException | IOException e) {
            throw new PackageDownloadException("Failed to download", e);
        }
    }
}
