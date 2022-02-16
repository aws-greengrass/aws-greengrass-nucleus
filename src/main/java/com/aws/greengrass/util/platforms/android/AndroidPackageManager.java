/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.vdurmont.semver4j.Semver;
import lombok.NonNull;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

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
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    void installAPK(@NonNull String apkPath, @NonNull String packageName, boolean force)
            throws IOException, InterruptedException;

    /**
     * Uninstall package from Android.
     *
     * @param packageName name of package to uninstall
     * @throws IOException on other errors
     * @throws InterruptedException when thread has been interrupted
     */
    void uninstallPackage(@NonNull String packageName)
            throws IOException, InterruptedException;

}
