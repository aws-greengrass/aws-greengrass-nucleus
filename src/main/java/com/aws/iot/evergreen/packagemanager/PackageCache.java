package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

@SuppressWarnings({"PMD.AvoidPrintStackTrace", "PMD.IdenticalCatchBranches"})
public class PackageCache {
    private static final Path LOCAL_CACHE_PATH =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");

    /**
     * Make sure all the specified packages exist in the package cache. Download them from remote repository if
     * they don't exist.
     *
     * @param pkgs a list of packages.
     * @return a future to notify once this is finished.
     */
    @SuppressFBWarnings(value = "NP_NONNULL_PARAM_VIOLATION", justification = "Waiting for package cache "
            + "implementation to be completed")
    public Future<Void> preparePackages(List<PackageIdentifier> pkgs) {
        // TODO: to be implemented.
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        completableFuture.complete(null);
        return completableFuture;
    }

    /**
     * Retrieve the recipe of a package.
     *
     * @param pkg package identifier
     * @return package recipe
     */
    public Package getRecipe(PackageIdentifier pkg) {
        // TODO: to be implemented.
        LocalPackageStore localPackageStore = new LocalPackageStore(LOCAL_CACHE_PATH);
        try {
            return localPackageStore.getPackage(pkg.getName(), pkg.getVersion()).get();
        } catch (PackagingException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
