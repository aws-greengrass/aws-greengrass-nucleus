/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.nucleus.androidservice;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidApplication;
import com.aws.greengrass.util.platforms.android.AndroidPackageIdentifier;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.vdurmont.semver4j.Semver;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

import com.aws.greengrass.android.service.NucleusForegroundService;

// Activity must be "singleTop" to handle in onNewIntent()
public class MainActivity extends AppCompatActivity implements AndroidApplication {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private Context context;

    // Package uninstall part
    private ConcurrentMap<String, UninstallResult> uninstallResults = new ConcurrentHashMap<>();

    private static final String PACKAGE_UNINSTALL_STATUS_ACTION
            = "com.aws.greengrass.nucleus.androidservice.MainActivity.PACKAGE_UNINSTALL_STATUS";
    private static final String PACKAGE_UNINSTALLED_EXTRA = "UninstallingPackageName";


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.start_btn).setOnClickListener(v -> NucleusForegroundService.launch());
    }


    // Package uninstall part
    private class UninstallResult {
        Integer status = null;
        Object lock = new Object();
    }


    /**
     * Receives intent with status of uninstall.
     *
     * @param intent Intent with status of uninstall
     * @note Activity must be "singleTop"
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Bundle extras = intent.getExtras();
        if (PACKAGE_UNINSTALL_STATUS_ACTION.equals(intent.getAction())) {
            int status = extras.getInt(PackageInstaller.EXTRA_STATUS);
            String message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String packageName = extras.getString(PACKAGE_UNINSTALLED_EXTRA);
            // TODO: set status specific for package name instead of generic
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    // This app isn't privileged, so the user has to confirm the uninstall.
                    Intent confirmIntent = (Intent) extras.get(Intent.EXTRA_INTENT);
//                    logger.atInfo().kv("package", packageName).log("Requesting uninstall confirmation from user");
                    Log.i(LOG_TAG, "Requesting uninstall confirmation from user");
                    startActivity(confirmIntent);
                    break;
                case PackageInstaller.STATUS_SUCCESS:
//                    logger.atInfo().kv("package", packageName).log("Uninstall succeeded");
                    Log.i(LOG_TAG, "Uninstall succeeded");
                    setUninstallStatus(packageName, status);
                    break;
                case PackageInstaller.STATUS_FAILURE:
                case PackageInstaller.STATUS_FAILURE_ABORTED:
                case PackageInstaller.STATUS_FAILURE_BLOCKED:
                case PackageInstaller.STATUS_FAILURE_CONFLICT:
                case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                case PackageInstaller.STATUS_FAILURE_INVALID:
                case PackageInstaller.STATUS_FAILURE_STORAGE:
                    setUninstallStatus(packageName, status);
//                    logger.atError().kv("package", packageName).kv("status", status).kv("message", message)
//                            .log("Uninstall failed!");
                    Log.e(LOG_TAG, "Uninstall failed!");
                    break;
                default:
                    setUninstallStatus(packageName, status);
//                    logger.atError().kv("package", packageName).kv("status", status)
//                            .log("Unrecognized status received from installer!");
                    Log.e(LOG_TAG, "Unrecognized status received from installer!");
            }
        }
    }

    /**
     * Save uninstall status and notify waiting threads
     * @param packageName  name of the packages to remove
     * @param newStatus status of removal
     */
    private void setUninstallStatus(String packageName, int newStatus) {
        UninstallResult result = uninstallResults.get(packageName);
        if (result != null && result.lock != null) {
            synchronized (result.lock) {
                result.status = newStatus;
                result.lock.notifyAll();
            }
        }
    }

    /**
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     * @return version of package or null if package does not installed
     * @throws IOException on errors
     */
    @Override
    public Semver getInstalledPackageVersion(@NonNull String packageName) throws IOException {
        // FIXME: android: implement
        throw new IOException("Not implemented yet");
    }

    /*
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     * @throws IOException on errors
     */
    //@Override
    //boolean isPackageInstalled(@NonNull String packageName, Semver version) throws IOException;

    /**
     * Gets APK package and version as AndroidPackageIdentifier object.
     *
     * @param apkPath path to APK file
     * @throws IOException on errors
     */
    @Override
    public AndroidPackageIdentifier getPackageInfo(@NonNull String apkPath) throws IOException {
        // FIXME: android: implement
        throw new IOException("Not implemented yet");
    }

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param msTimeout timeout in milliseconds
     * @throws IOException      on errors
     * @throws TimeoutException when operation was timed out
     */
    @Override
    public void installAPK(@NonNull String apkPath, long msTimeout) throws IOException, TimeoutException {
        // FIXME: android: implement
        // what todo in cased of time out ? Android or use can install APK successfully even when timed out here
        throw new IOException("Not implemented yet");
    }

    /**
     * Uninstall package from Android.
     *
     * @param packageName name of package to uninstall
     * @param msTimeout   timeout in milliseconds
     * @throws TimeoutException when operation was timed out
     * @throws IOException on other errors
     */
    @Override
    public void uninstallPackage(@NonNull String packageName, long msTimeout) throws IOException, TimeoutException {
        //  for simple implementation see https://android.googlesource.com/platform/development/+/master/samples/ApiDemos/src/com/example/android/apis/content/InstallApk.java

        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(PACKAGE_UNINSTALL_STATUS_ACTION);
        intent.putExtra(PACKAGE_UNINSTALLED_EXTRA, packageName);
        PendingIntent sender = PendingIntent.getActivity(context, 0, intent, 0);
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        IntentSender statusReceiver = sender.getIntentSender();

        UninstallResult result = uninstallResults.computeIfAbsent(packageName, k -> new UninstallResult());
        try {
            synchronized (result.lock) {
                result.status = null;
                packageInstaller.uninstall(packageName, statusReceiver);

                try {
                    result.wait(msTimeout);
                } catch (InterruptedException e) {
                    throw new IOException("Execution has been interrupted", e);
                }

                if (result.status == null) {
                    throw new TimeoutException("Timed out when waiting to uninstall package");
                }

                int res = result.status.intValue();
                if (res != PackageInstaller.STATUS_SUCCESS) {
                    throw new IOException("Uninstall failed with status " + res);
                }
            }
        } finally {
            uninstallResults.remove(packageName);
        }
    }

    /**
     * Get user id of current user.
     *
     * @return uid of current user.
     */
    @Override
    public long getUID() {
        ActivityManager activityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo processInfo = activityManager.getRunningAppProcesses().get(0);
        return processInfo.uid;
    }
}
