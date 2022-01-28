/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android;

import android.net.Uri;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.vdurmont.semver4j.Semver;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import lombok.NonNull;

/**
 * Interface to control Android packages  like install/uninstall APK, get info, check installation status.
 */
public abstract class AndroidPackageManager {
    private static AndroidPackageManager INSTANCE;
    protected static final Logger logger = LogManager.getLogger(AndroidPackageManager.class);

    /**
     *  Get the android package manager instance.
     *
     * @return AndroidPackageManager interface instance
     */
    public static AndroidPackageManager getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        INSTANCE = new AndroidPackageManagerFS();
        logger.atInfo().log("Getting android package manager instance {}.", INSTANCE.getClass().getName());
        return INSTANCE;
    }

    /**
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     */
    public abstract Semver isPackageInstalled(@NonNull String packageName) throws IOException;

    /**
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     */
//    public abstract boolean isPackageInstalled(@NonNull String packageName, Semver version) throws IOException;

    /**
     * Gets APK package and version as AndroidPackageIdentifier object
     *
     * @param ApkPath path to APK file
     * @throws IOException on errors
     */
    public abstract AndroidPackageIdentifier getPackageInfo(Uri ApkPath) throws IOException;

    /**
     * Install APK file
     *
     * @param ApkPath path to APK file
     * @param msTimeout timeout in milliseconds
     * @throws TimeoutException when operation was timed out, IOException otherwise
     */
    public abstract void InstallAPK(Uri ApkPath, long msTimeout) throws IOException, TimeoutException;

    /**
     * Uninstall package from Android
     *
     * @param packageName name of package to uninstall
     * @param msTimeout timeout in milliseconds
     * @throws TimeoutException when operation was timed out, IOException otherwise
     */
    public abstract void UninstallPackage(@NonNull String packageName, long msTimeout) throws IOException, TimeoutException;
}
