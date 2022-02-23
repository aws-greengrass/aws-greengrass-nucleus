/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;
import lombok.NonNull;

import java.io.IOException;
import java.util.concurrent.Callable;
import javax.annotation.Nullable;

/**
 * Interface to control Android packages install/uninstall APK, get info, check installation status.
 */
public interface AndroidPackageManager {
    /**
     * Checks is Android packages installed and return it versions.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version and version code of package or null if package does not installed
     * @throws IOException on errors
     */
    AndroidPackageIdentifier getPackageInfo(@NonNull String packageName) throws IOException;

    /**
     * Gets APK package and version as AndroidPackageIdentifier object.
     *
     * @param apkPath path to APK file
     * @throws IOException on errors
     */
    AndroidPackageIdentifier getAPKInfo(@NonNull String apkPath) throws IOException;

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param packageName APK should contains that package
     * @param force force install even when package with same name and version is already installed
     * @param logger optional logger to log to
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    void installAPK(@NonNull String apkPath, @NonNull String packageName, boolean force,
                    @Nullable Logger logger) throws IOException, InterruptedException;


    /**
     * Get APK installer callable.
     *
     * @param cmdLine #install_package command line to parse
     * @param packageName APK should contains that package
     * @param logger optional logger to log to
     * @return Callable callable to call installAPK()
     * @throws IOException      on errors
     */
    Callable<Integer> getApkInstaller(String cmdLine, String packageName, @Nullable Logger logger)
            throws IOException;

    /**
     * Uninstall package from Android.
     *
     * @param packageName name of package to uninstall
     * @param logger optional logger to log to
     * @throws IOException on other errors
     * @throws InterruptedException when thread has been interrupted
     */
    void uninstallPackage(@NonNull String packageName, @Nullable Logger logger)
            throws IOException, InterruptedException;

}
