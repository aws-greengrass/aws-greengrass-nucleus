package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageDownloadException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStoreDeprecated;

import java.io.IOException;
import java.nio.file.Path;

public class GreengrassPackageServiceHelper {
    //TODO connect to cloud service
    Package downloadPackageRecipe(PackageIdentifier packageIdentifier, Path packageStoreDirectory)
            throws PackageDownloadException {
        // TODO: to be implemented.
        LocalPackageStoreDeprecated localPackageStore = new LocalPackageStoreDeprecated(packageStoreDirectory);
        try {
            return localPackageStore.getPackage(packageIdentifier.getName(), packageIdentifier.getVersion()).get();
        } catch (PackagingException | IOException e) {
            throw new PackageDownloadException("Failed to download", e);
        }
    }
}
