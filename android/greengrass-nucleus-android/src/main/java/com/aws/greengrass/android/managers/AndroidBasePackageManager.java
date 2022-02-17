/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.core.content.FileProvider;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.utils.LazyLogger;
import com.aws.greengrass.util.platforms.android.AndroidPackageIdentifier;
import com.aws.greengrass.util.platforms.android.AndroidPackageManager;
import com.vdurmont.semver4j.Semver;
import lombok.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarException;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME;


/**
 * Basic implementation of AndroidPackageManager interface.
 */
public class AndroidBasePackageManager extends LazyLogger implements AndroidPackageManager {
    // Package install part.
    private static final String PACKAGE_ARCHIVE = "application/vnd.android.package-archive";
    private static final String PROVIDER = ".provider";
    private static final long INSTALL_POLL_INTERVAL = 200;

    // Package uninstall part.
    public static final String PACKAGE_UNINSTALL_STATUS_ACTION
            = "com.aws.greengrass.PACKAGE_UNINSTALL_STATUS";
    private static final String EXTRA_REQUEST_ID = "RequestId";

    // In-process uninstall requests.
    private ConcurrentMap<String, UninstallResult> uninstallRequests = new ConcurrentHashMap<>();

    // Reference to context provider
    private final AndroidContextProvider contextProvider;

    /**
     * Store result of uninstall operation.
     */
    private class UninstallResult {
        private Integer status;
        private String message;
    }


    /**
     * Constructor.
     *
     * @param contextProvider reference to context getter
     */
    public AndroidBasePackageManager(AndroidContextProvider contextProvider) {
        this.contextProvider = contextProvider;
    }

    // Implementation of methods from AndroidPackageManager interface.
    /**
     * Checks is Android packages installed and return it versions.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version and version code of package
     * @throws IOException on errors
     */
    @Override
    public AndroidPackageIdentifier getPackageInfo(@NonNull String packageName) throws IOException {
        PackageInfo packageInfo = getInstalledPackageInfo(packageName);
        if (packageInfo == null) {
            return null;
        }

        return createAndroidPackageIdentifier(packageInfo);
    }

    /**
     * Gets APK's package and versions as AndroidPackageIdentifier object.
     *
     * @param apkPath path to APK file
     * @throws IOException on errors
     */
    @Override
    public AndroidPackageIdentifier getAPKInfo(@NonNull String apkPath) throws IOException {
        PackageInfo packageInfo = getApkPackageInfo(apkPath);

        return createAndroidPackageIdentifier(packageInfo);
    }

    /**
     * Create AndroidPackageIdentifier from Android PackageInfo.
     *
     * @param packageInfo Android PackageInfo
     */
    private AndroidPackageIdentifier createAndroidPackageIdentifier(PackageInfo packageInfo) {
        return new AndroidPackageIdentifier(packageInfo.packageName,
                new Semver(packageInfo.versionName), getVersionCode(packageInfo));
    }

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param packageName APK should contains that package
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    @Override
    public void installAPK(@NonNull String apkPath, @NonNull String packageName, boolean force)
            throws IOException, InterruptedException {

        logDebug("Installing {} from {}", packageName, apkPath);

        // check for interruption
        if (Thread.currentThread().isInterrupted()) {
            logWarn("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        // get info about APK
        PackageInfo apkPackageInfo = getApkPackageInfo(apkPath);
        long apkVersionCode = getVersionCode(apkPackageInfo);
        logDebug("APK contains package {} version {} versionCode {}", apkPackageInfo.packageName,
                apkPackageInfo.versionName, apkVersionCode);

        // check is APK provide required package
        if (!packageName.equals(apkPackageInfo.packageName)) {
            throw new IOException(String.format("APK provides package %s but %s expected",
                    apkPackageInfo.packageName, packageName));
        }

        // check for interruption
        if (Thread.currentThread().isInterrupted()) {
            logWarn("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        // check is package already installed
        long installedVersionCode = -1;
        long lastUpdateTime = -1;
        PackageInfo installedPackageInfo = getInstalledPackageInfo(packageName);
        if (installedPackageInfo != null) {
            installedVersionCode = getVersionCode(installedPackageInfo);
            lastUpdateTime = installedPackageInfo.lastUpdateTime;
            // check versions of package
            if (!force && apkVersionCode == installedVersionCode
                    && apkPackageInfo.versionName.equals(installedPackageInfo.versionName)) {
                logDebug("Package {} with same version and versionCode is already installed, nothing to do",
                        packageName);
                return;
            }
        }

        // check for interruption
        if (Thread.currentThread().isInterrupted()) {
            logWarn("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        boolean uninstalled = false;
        // check is uninstall required
        if (installedVersionCode != -1 && apkVersionCode < installedVersionCode) {
            logDebug("Uninstalling package {} first due to downgrade is required from {} to {}",
                    packageName, installedVersionCode, apkVersionCode);
            uninstallPackage(packageName);
            uninstalled = true;
        }

        // check for interruption but only when package was not uninstalled
        if (!uninstalled && Thread.currentThread().isInterrupted()) {
            logWarn("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        // finally install APK without checks
        installAPK(apkPath, packageName, lastUpdateTime);
    }

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param packageName APK should contains that package
     * @param lastUpdateTime previous package last update time to check for installation
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    // TODO: android: rework with PackageInstaller
    private void installAPK(@NonNull String apkPath, @NonNull String packageName,
                            long lastUpdateTime)
            throws IOException, InterruptedException {
        File apkFile = new File(apkPath);
        Intent intent = new Intent(ACTION_VIEW);
        Context context = contextProvider.getContext();
        Uri downloadedApk = FileProvider.getUriForFile(
                context,
                context.getPackageName() + PROVIDER,
                apkFile);
        intent.setDataAndType(downloadedApk, PACKAGE_ARCHIVE);
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);

        while (true) {
            Thread.sleep(INSTALL_POLL_INTERVAL);

            // check is package has been (re)installed
            PackageInfo newPackageInfo = getInstalledPackageInfo(packageName);
            if (newPackageInfo != null && newPackageInfo.lastUpdateTime > lastUpdateTime) {
                break;
            }
        }
    }

    /**
     * Get long version code from PackageInfo.
     *
     * @param packageInfo PackageInfo object
     * @return versionCode as long
     */
    private static long getVersionCode(@NonNull PackageInfo packageInfo) {
        long versionCode;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            versionCode = packageInfo.getLongVersionCode();
        } else {
            versionCode = packageInfo.versionCode;
        }
        return versionCode;
    }

    /**
     * Read information about APK file.
     *
     * @param apkPath path to APK file
     * @return PackageInfo object
     * @throws IOException on errors
     */
    private @NonNull PackageInfo getApkPackageInfo(@NonNull String apkPath) throws IOException {
        File apkFile = new File(apkPath);
        if (!apkFile.exists()) {
            throw new FileNotFoundException(String.format("File %s not found", apkPath));
        }

        Context context = contextProvider.getContext();
        PackageManager pm = context.getPackageManager();
        PackageInfo packageInfo = pm.getPackageArchiveInfo(apkPath, 0);
        if (packageInfo == null) {
            throw new JarException(String.format("Could not get package information from %s", apkPath));
        }
        return packageInfo;
    }

    /**
     * Get information about installed package.
     *
     * @param packageName name of package to check.
     * @return null if package does not installed or PackageInfo with package information.
     * @throws IOException on errors
     */
    private PackageInfo getInstalledPackageInfo(@NonNull String packageName) throws IOException {
        Context context = contextProvider.getContext();
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);
            if (packageInfo == null) {
                throw new IOException(String.format("Could not get package information for package %s", packageName));
            }
            return packageInfo;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Uninstall package from Android.
     *
     * @param packageName name of package to uninstall
     * @throws IOException on other errors
     * @throws InterruptedException when thread has been interrupted
     */
    @Override
    public void uninstallPackage(@NonNull String packageName)
            throws IOException, InterruptedException {
        //  for simple implementation see https://android.googlesource.com/platform/development/+/master/samples/ApiDemos/src/com/example/android/apis/content/InstallApk.java

        // first check is package installed
        PackageInfo installedPackageInfo = getInstalledPackageInfo(packageName);
        if (installedPackageInfo == null) {
            logDebug("Package {} doesn't installed, nothing to do", packageName);
            return;
        }

        Intent intent = new Intent(PACKAGE_UNINSTALL_STATUS_ACTION);
        Context context = contextProvider.getContext();
        intent.setPackage(context.getPackageName());

        String requestId = getRandomRequestId();
        intent.putExtra(EXTRA_REQUEST_ID, requestId);


        // prepare everything required by PackageInstaller
        PendingIntent sender = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        IntentSender statusReceiver = sender.getIntentSender();

        UninstallResult result = new UninstallResult();
        uninstallRequests.put(requestId, result);
        try {
            synchronized (result) {
                packageInstaller.uninstall(packageName, statusReceiver);

                result.wait();

                Integer status = result.status;
                if (status == null) {
                    throw new IOException("Uninstall failed, status unknown");
                }

                if (status != PackageInstaller.STATUS_SUCCESS) {
                    if (result.message != null) {
                        throw new IOException("Uninstall failed, status " + status + " message " + result.message);
                    } else {
                        throw new IOException("Uninstall failed, status " + status);
                    }
                }
            }
        } finally {
            uninstallRequests.remove(requestId);
        }
    }

    /**
     * Handle result of uninstall.
     *
     * @param intent information about uninstall operation status.
     */
    public void handlerUninstallResult(Intent intent) {
        Bundle extras = intent.getExtras();
        int status = extras.getInt(PackageInstaller.EXTRA_STATUS);
        String message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String packageName = extras.getString(EXTRA_PACKAGE_NAME);
        String requestId = extras.getString(EXTRA_REQUEST_ID);
        // TODO: set status specific for package name instead of generic
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // This app isn't privileged, so the user has to confirm the uninstall.
                Intent confirmIntent = (Intent) extras.get(Intent.EXTRA_INTENT);
                confirmIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                logDebug("Requesting uninstall of {} confirmation from user", packageName);
                Context context = contextProvider.getContext();
                context.startActivity(confirmIntent);
                break;
            case PackageInstaller.STATUS_SUCCESS:
                logDebug("Uninstalling of {} succeeded", packageName);
                setUninstallStatus(requestId, status, message);
                break;
            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                setUninstallStatus(requestId, status, message);
                logError("Uninstalling of {} failed, status {} message {}",
                        packageName, status, message);
                break;
            default:
                setUninstallStatus(requestId, status, message);
                logError("Unrecognized status received from installer when uninstall {} status {}",
                        packageName, status);
        }
    }

    /**
     * Save uninstall status and notify waiting threads.
     *
     * @param requestId  Id of request
     * @param status status of removal
     * @param message message from installer
     */
    private void setUninstallStatus(String requestId, int status, String message) {
        UninstallResult result = uninstallRequests.get(requestId);
        if (result != null) {
            synchronized (result) {
                result.status = new Integer(status);
                result.message = message;
                result.notifyAll();
            }
        }
    }

    /**
     * Generate random UUID string.
     *
     * @return random string based on UUID
     */
    private String getRandomRequestId() {
        UUID uuid = UUID.randomUUID();
        String uuidAsString = uuid.toString();
        return uuidAsString;
    }
}
