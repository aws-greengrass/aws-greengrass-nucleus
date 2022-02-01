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
 * Interface to control Android packages  like install/uninstall APK, get info, check installation status.
 */
public interface AndroidPackageManager {
    /**
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     * @throws IOException on errors
     */
    Semver getInstalledPackageVersion(@NonNull String packageName) throws IOException;

    /*
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     * @throws IOException on errors
     */
    //boolean isPackageInstalled(@NonNull String packageName, Semver version) throws IOException;

    /**
     * Gets APK package and version as AndroidPackageIdentifier object.
     *
     * @param apkPath path to APK file
     * @throws IOException on errors
     */
    AndroidPackageIdentifier getPackageInfo(@NonNull String apkPath) throws IOException;

    /**
     * Install APK file.
     *
     * @param apkPath path to APK file
     * @param msTimeout timeout in milliseconds
     * @throws IOException on errors
     * @throws TimeoutException when operation was timed out
     */
    void installAPK(@NonNull String apkPath, long msTimeout) throws IOException, TimeoutException;

    /**
     * Uninstall package from Android.
     *
     * @param packageName name of package to uninstall
     * @param msTimeout timeout in milliseconds
     * @throws IOException on errors
     * @throws TimeoutException when operation was timed out
     */
    void uninstallPackage(@NonNull String packageName, long msTimeout) throws IOException, TimeoutException;
}
