/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#if ANDROID
package com.aws.greengrass.android.managers;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.aws.greengrass.android.utils.NucleusContentProvider;
import com.aws.greengrass.nucleus.R;

import java.io.File;
import java.util.List;

public class BaseComponentManager implements AndroidComponentManager {

    public static final String PACKAGE_ARCHIVE = "application/vnd.android.package-archive";
    public static final String PROVIDER = ".provider";

    @Override
    public boolean installPackage(String path, String packageName) {
        boolean result = false;
        if (path != null && packageName != null) {
            Application app = NucleusContentProvider.getApp();
            File apkFile = new File(path);
            if (apkFile.exists()) {
                int apkApkVersionCode = getApkVersionCode(path);
                int installedVersionCode = getPackageVersionCode(packageName);
                if (installedVersionCode > apkApkVersionCode) {
                    return false;
                }
                try {
                    Intent intent = new Intent(ACTION_VIEW);
                    Uri downloaded_apk = FileProvider.getUriForFile(
                            app,
                            app.getPackageName() + PROVIDER,
                            apkFile);
                    intent.setDataAndType(downloaded_apk, PACKAGE_ARCHIVE);
                    intent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_GRANT_READ_URI_PERMISSION);
                    app.startActivity(intent);
                    result = true;
                } catch (Exception e) {
                    e.printStackTrace();
                    result = false;
                }
            }
        }
        return result;
    }

    @Override
    public void uninstallPackage() {

    }

    @Override
    public boolean startActivity(String packageName, String className, String action) {
        boolean result = false;
        if (packageName != null
                && className != null
                && action != null) {
            Application app = NucleusContentProvider.getApp();
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName, className));
            intent.setAction(action);
            if (intent.resolveActivityInfo(app.getPackageManager(), 0) != null) {
                result = true;
                NotManager
                        .notForActivityComponent(app, intent, app.getString(R.string.click_to_start_component));
            }
        }
        return result;
    }

    @Override
    public boolean stopActivity(String packageName, String className, String action) {
        boolean result = false;
        if (packageName != null
                && action != null) {
            Application app = NucleusContentProvider.getApp();
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(packageName, className));
            intent.setAction(action);
            intent.setPackage(packageName);
            if (intent.resolveActivityInfo(app.getPackageManager(), 0) != null) {
                intent.setComponent(null);
                app.sendBroadcast(intent);
                result = true;
            }
        }
        return result;
    }

    @Override
    public boolean startService(String packageName, String className, String action) {
        boolean result = false;
        if (packageName != null && className != null && action != null) {
            Application app = NucleusContentProvider.getApp();
            Intent intent = new Intent();
            intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(action);
            intent.setComponent(new ComponentName(packageName, className));
            List<ResolveInfo> matches = app.getPackageManager().queryIntentServices(intent, 0);
            if (matches.size() == 1) {
                try {
                    ContextCompat.startForegroundService(app, intent);
                    result = true;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return result;
    }

    @Override
    public boolean stopService(String packageName, String className, String action) {
        boolean result = false;
        if (packageName != null && className != null && action != null) {
            Application app = NucleusContentProvider.getApp();
            Intent intent = new Intent();
            intent.setAction(action);
            intent.setComponent(new ComponentName(packageName, className));
            List<ResolveInfo> matches = app.getPackageManager().queryIntentServices(intent, 0);
            if (matches.size() == 1) {
                intent.setComponent(null);
                app.sendBroadcast(intent);
                result = true;
            }
        }
        return result;
    }

    @Override
    public boolean isPackageInstalled(String packageName, Long curLastUpdateTime) {
        boolean result = false;
        Application app = NucleusContentProvider.getApp();
        PackageManager pm = app.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, 0);
            if (info.lastUpdateTime > curLastUpdateTime) {
                result = true;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return result;
    }

    @Override
    public long getPackageLastUpdateTime(String packageName) {
        Application app = NucleusContentProvider.getApp();
        try {
            return app.getPackageManager().getPackageInfo(packageName, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return -1;
        }
    }

    private int getApkVersionCode(String path) {
        int result = -1;
        Application app = NucleusContentProvider.getApp();
        PackageManager pm = app.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(path, 0);
        if (info != null) {
            result = info.versionCode;
        }
        return result;
    }

    private int getPackageVersionCode(String packageName) {
        int result = -1;
        Application app = NucleusContentProvider.getApp();
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
#endif

