/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.vdurmont.semver4j.Semver;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import lombok.NonNull;

/**
 * Implementation of AndroidPackageManager bases on Foreground service
 */
public class AndroidPackageManagerFS implements AndroidPackageManager {
    private static AndroidPackageManager INSTANCE;
    protected static final Logger logger = LogManager.getLogger(AndroidPackageManagerFS.class);
    

    private static final String PACKAGE_UNINSTALLED_ACTION = "com.aws.greengrass.util.AndroidPackageManager.PACKAGE_UNINSTALLED";
    private static final String PACKAGE_UNINSTALLED_EXTRA = "UninstalledPackageName";

    /**
     *
     */
    private class UninstallStatusReceiver extends Activity {
        Integer uninstallStatus;

        private void setUninstallStatus(int status) {
            synchronized (uninstallStatus) {
                uninstallStatus = status;
                uninstallStatus.notifyAll();
            }
        }

        protected int doUninstall(String packageName, long timeout) throws InterruptedException, TimeoutException {
            Context context = UninstallStatusReceiver.this;

            Intent intent = new Intent(context, UninstallStatusReceiver.class);
            intent.setAction(PACKAGE_UNINSTALLED_ACTION);
            intent.putExtra(PACKAGE_UNINSTALLED_EXTRA, packageName);
            PendingIntent sender = PendingIntent.getActivity(context, 0, intent, 0);
            PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
            IntentSender statusReceiver = sender.getIntentSender();

            int result;
            synchronized (uninstallStatus) {
                uninstallStatus = null;
                packageInstaller.uninstall(packageName, statusReceiver);

                uninstallStatus.wait(timeout);
                if (uninstallStatus == null) {
                    throw new TimeoutException("Timed out when waiting to uninstall package");
                }
                result = uninstallStatus.intValue();
            }
            return result;
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
        }

        @Override
        protected void onNewIntent(Intent intent) {
            Bundle extras = intent.getExtras();
            if (PACKAGE_UNINSTALLED_ACTION.equals(intent.getAction())) {
                int status = extras.getInt(PackageInstaller.EXTRA_STATUS);
                String message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE);
                String packageName = extras.getString(PACKAGE_UNINSTALLED_EXTRA);
                switch (status) {
                    case PackageInstaller.STATUS_PENDING_USER_ACTION:
                        // This app isn't privileged, so the user has to confirm the uninstall.
                        Intent confirmIntent = (Intent) extras.get(Intent.EXTRA_INTENT);
                        logger.atInfo().kv("package", packageName).log("Requesting uninstall confirmation from user");
                        startActivity(confirmIntent);
                        break;
                    case PackageInstaller.STATUS_SUCCESS:
                        logger.atInfo().kv("package", packageName).log("Uninstall succeeded");
                        setUninstallStatus(status);
                        break;
                    case PackageInstaller.STATUS_FAILURE:
                    case PackageInstaller.STATUS_FAILURE_ABORTED:
                    case PackageInstaller.STATUS_FAILURE_BLOCKED:
                    case PackageInstaller.STATUS_FAILURE_CONFLICT:
                    case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                    case PackageInstaller.STATUS_FAILURE_INVALID:
                    case PackageInstaller.STATUS_FAILURE_STORAGE:
                        setUninstallStatus(status);
                        logger.atError().kv("package", packageName).kv("status", status).kv("message", message).log("Uninstall failed!");
                        break;
                    default:
                        setUninstallStatus(status);
                        logger.atError().kv("package", packageName).kv("status", status).log("Unrecognized status received from installer!");
                }
            }
        }
    }


    public static AndroidPackageManager getInstance() {
        if (INSTANCE == null)
            synchronized (AndroidPackageManagerFS.class) {
                if (INSTANCE == null)
                    INSTANCE = new AndroidPackageManagerFS();
            }
        return INSTANCE;
    }

    /**
     * Default Constructor.
     */
    private AndroidPackageManagerFS() {
        super();
    }


    /**
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     */
    @Override
    public Semver getInstalledPackageVersion(@NonNull String packageName) throws IOException {
        // FIXME: android: implement
        throw new IOException("Not implemented yet");
    }

    /**
     * Checks is Android packages installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     *
     * @return version of package or null if package does not installed
     */
/*
    Override
    boolean isPackageInstalled(@NonNull String packageName, Semver version) throws IOException {
        // FIXME: android: implement
        throw new IOException("Not implemented yet");
    }
*/

    /**
     * Gets APK package and version as AndroidPackageIdentifier object
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
     * Install APK file
     *
     * @param apkPath path to APK file
     * @param msTimeout timeout in milliseconds
     * @throws TimeoutException when operation was timed out, IOException otherwise
     */
    @Override
    public void installAPK(@NonNull String apkPath, long msTimeout) throws IOException, TimeoutException {
        // FIXME: android: implement
        // what todo in cased of time out ? Android or use can install APK successfully even when timed out here
        throw new IOException("Not implemented yet");
    }

    /**
     * Uninstall package from Android
     *
     * @param packageName name of package to uninstall
     * @param msTimeout timeout in milliseconds
     * @throws TimeoutException when operation was timed out, IOException otherwise
     */
    @Override
    public void uninstallPackage(@NonNull String packageName, long msTimeout) throws IOException, TimeoutException {
        //  simple implementation https://android.googlesource.com/platform/development/+/master/samples/ApiDemos/src/com/example/android/apis/content/InstallApk.java

        // TODO: check package is installed first
        try {
            // FIXME: rework to getting reference on Application
            UninstallStatusReceiver sr = new UninstallStatusReceiver();
            int status = sr.doUninstall(packageName, msTimeout);
        } catch (InterruptedException e) {
            throw new IOException(e);
        }
    }
}
