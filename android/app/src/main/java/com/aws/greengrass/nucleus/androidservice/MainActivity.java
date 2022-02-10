/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.nucleus.androidservice;


import android.app.Application;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import com.aws.greengrass.android.service.NucleusForegroundService;
import com.aws.greengrass.util.platforms.android.AndroidAppLevelAPI;
import com.aws.greengrass.util.platforms.android.AndroidPackageIdentifier;
import com.vdurmont.semver4j.Semver;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;

// Activity must be "singleTop" to handle in onNewIntent()
public class MainActivity extends AppCompatActivity implements AndroidAppLevelAPI {
    // Package uninstall part
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private static final String PACKAGE_UNINSTALL_STATUS_ACTION
            = "com.aws.greengrass.PACKAGE_UNINSTALL_STATUS";
    private static final String PACKAGE_NAME = "PackageName";
    private static final String REQUEST_ID = "RequestId";

    private ConcurrentMap<String, UninstallResult> uninstallRequests = new ConcurrentHashMap<>();

    private class UninstallResult {
        private Integer status;
        private String message;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.start_btn).setOnClickListener(v -> {
            NucleusForegroundService.launch(this.getApplicationContext(), this);
        });
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
            String packageName = extras.getString(PACKAGE_NAME);
            int requestId = extras.getInt(REQUEST_ID);
            // TODO: set status specific for package name instead of generic
            switch (status) {
                case PackageInstaller.STATUS_PENDING_USER_ACTION:
                    // This app isn't privileged, so the user has to confirm the uninstall.
                    Intent confirmIntent = (Intent) extras.get(Intent.EXTRA_INTENT);
                    Log.i(LOG_TAG, "Requesting uninstall of " + packageName + " confirmation from user");
                    startActivity(confirmIntent);
                    break;
                case PackageInstaller.STATUS_SUCCESS:
                    Log.i(LOG_TAG, "Uninstalling of " + packageName + " succeeded");
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
                    Log.e(LOG_TAG, "Uninstalling of " + packageName + " failed, status "
                            + status + " message " + message);
                    break;
                default:
                    setUninstallStatus(requestId, status, message);
                    Log.e(LOG_TAG, "Unrecognized status received from installer when uninstall "
                            + packageName + " status " + status);
            }
        }
    }

    /**
     * Save uninstall status and notify waiting threads.
     *
     * @param requestId  Id of request
     * @param status status of removal
     * @param message message from installer
     */
    private void setUninstallStatus(int requestId, int status, String message) {
        UninstallResult result = uninstallRequests.get(requestId);
        if (result != null) {
            synchronized (result) {
                result.status = new Integer(status);
                result.message = message;
                result.notifyAll();
            }
        }
    }

    /* TODO: android: probably we should move these methods
       from AndroidPackageManager to be implemented in separate object
    */
    /**
     * Checks is Android package installed and return it version.
     *
     * @param packageName Name of Android package to check installation status
     * @return version of package or null if package does not installed
     * @throws IOException on errors
     */
    @Override
    public Semver getInstalledPackageVersion(@NonNull String packageName) throws IOException {
        // FIXME: android: implement
        // TODO: use uninstallPackage
        throw new IOException("Not implemented yet");
    }

    /*
     * Checks is Android package with that version installed.
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
     * Generate random UUID string.
     *
     * @return random string based on UUID
     */
    private String getRandomRequestId() {
        UUID uuid = UUID.randomUUID();
        String uuidAsString = uuid.toString();
        return uuidAsString;
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

        // TODO: first check is package installed

        // create intent will sent to status receiver
        Intent intent = new Intent(this, MainActivity.class);
        intent.setAction(PACKAGE_UNINSTALL_STATUS_ACTION);
        intent.putExtra(PACKAGE_NAME, packageName);
        String requestId = getRandomRequestId();
        intent.putExtra(REQUEST_ID, requestId);

        // prepare everything required by PackageInstaller
        PendingIntent sender = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
        IntentSender statusReceiver = sender.getIntentSender();

        UninstallResult result = new UninstallResult();
        uninstallRequests.put(requestId, result);
        try {
            synchronized (result) {
                packageInstaller.uninstall(packageName, statusReceiver);

                try {
                    if (msTimeout >= 0) {
                        result.wait(msTimeout);
                    } else {
                        result.wait();
                    }
                } catch (InterruptedException e) {
                    throw new IOException("Execution has been interrupted", e);
                }

                Integer status = result.status;
                if (status == null) {
                    throw new TimeoutException("Timed out when waiting to uninstall package");
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

    // TODO: join implementations
    public static final String PACKAGE_ARCHIVE = "application/vnd.android.package-archive";
    public static final String PROVIDER = ".provider";

    @Override
    public boolean installPackage(String path, String packageName) {
        boolean result = false;
        if (path != null && packageName != null) {
            Application app = getApplication();
            File apkFile = new File(path);
            if (apkFile.exists()) {
                int apkApkVersionCode = getApkVersionCode(path);
                int installedVersionCode = getPackageVersionCode(packageName);
                if (installedVersionCode > apkApkVersionCode) {
                    return false;
                }
                try {
                    Intent intent = new Intent(ACTION_VIEW);
                    Uri downloadedApk = FileProvider.getUriForFile(
                            app,
                            app.getPackageName() + PROVIDER,
                            apkFile);
                    intent.setDataAndType(downloadedApk, PACKAGE_ARCHIVE);
                    intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION);
                    app.startActivity(intent);
                    result = true;
                } catch (Throwable e) {
                    e.printStackTrace();
                    result = false;
                }
            }
        }
        return result;
    }

    @Override
    public boolean isPackageInstalled(String packageName, Long curLastUpdateTime) {
        boolean result = false;
        Application app = getApplication();
        PackageManager pm = app.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            if (info.lastUpdateTime > curLastUpdateTime) {
                result = true;
            }
        } catch (PackageManager.NameNotFoundException e) {
            // e.printStackTrace();
        }
        return result;
    }

    @Override
    public long getPackageLastUpdateTime(String packageName) {
        Application app = getApplication();
        try {
            return app.getPackageManager().getPackageInfo(packageName, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            // e.printStackTrace();
            return -1;
        }
    }

    private int getApkVersionCode(String path) {
        int result = -1;
        Application app = getApplication();
        PackageManager pm = app.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(path, 0);
        if (info != null) {
            result = info.versionCode;
        }
        return result;
    }

    private int getPackageVersionCode(String packageName) {
        int result = -1;
        Application app = getApplication();
        PackageManager pm = app.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            if (info != null) {
                result = info.versionCode;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return result;
    }
}
