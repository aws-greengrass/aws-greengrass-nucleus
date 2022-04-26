/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.managers;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import androidx.core.content.FileProvider;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPackageIdentifier;
import com.aws.greengrass.util.platforms.android.AndroidPackageManager;
import com.aws.greengrass.util.platforms.android.AndroidVirtualCmdExecution;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarException;
import javax.annotation.Nullable;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;

/**
 * Basic implementation of AndroidPackageManager interface.
 */
public class AndroidBasePackageManager implements AndroidPackageManager {
    // Package install part.
    public static final String APK_INSTALL_CMD = "#install_package";
    private static final String APK_INSTALL_CMD_EXAMPLE = "#install_package path_to.apk [force[=false]]";
    private static final String APK_INSTALL_FORCE = "force";
    private static final String PACKAGE_ARCHIVE = "application/vnd.android.package-archive";
    private static final String PROVIDER = ".provider";
    private static final long INSTALL_POLL_INTERVAL = 200;

    // Package uninstall part.
    private static final String PACKAGE_UNINSTALL_STATUS_ACTION
            = "com.aws.greengrass.PACKAGE_UNINSTALL_STATUS";
    private static final String EXTRA_REQUEST_ID = "RequestId";

    // that Logger is backed to greengrass.log
    private final Logger classLogger;

    // In-process uninstall requests.
    private final ConcurrentMap<String, UninstallContext> uninstallRequests = new ConcurrentHashMap<>();

    // Reference to context provider
    private final AndroidContextProvider contextProvider;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (PACKAGE_UNINSTALL_STATUS_ACTION.equals(action)) {
                    handleUninstallResult(intent);
                }
            } catch (Throwable e) {
                classLogger.atError().setCause(e)
                        .log("Error while processing incoming intent in BroadcastReceiver");
            }
        }
    };

    @AllArgsConstructor
    private final class Installer extends AndroidVirtualCmdExecution {
        private String apkPath;
        private String packageName;
        private boolean force;
        private Logger logger;

        @Override
        public void startup() {
        }

        @Override
        public int run() throws IOException, InterruptedException {
            installAPK(apkPath, packageName, force, logger);
            return 0;
        }

        @Override
        public void shutdown() {
        }
    }

    /**
     * Store result of uninstall operation.
     */
    private class UninstallContext {
        private Integer status;
        private String message;
        private Logger logger;

        UninstallContext(Logger logger) {
            this.logger = logger;
        }
    }


    /**
     * Creates instance of AndroidBasePackageManager.
     *
     * @param contextProvider reference to context getter
     */
    public AndroidBasePackageManager(AndroidContextProvider contextProvider) {
        classLogger = LogHelper.getLogger(contextProvider.getContext().getFilesDir(), getClass());
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
     * Get APK installer execution.
     *
     * @param cmdLine #install_package command line
     * @param packageName APK should contains that package
     * @param logger logger to log to
     * @return Callable Object to call installAPK()
     * @throws IOException      on errors
     */
    @Override
    public AndroidVirtualCmdExecution getApkInstaller(String cmdLine, String packageName,
                                                      @Nullable Logger logger) throws IOException {
        if (Utils.isEmpty(cmdLine)) {
            throw new IOException("Expected " + APK_INSTALL_CMD + " command but got empty line");
        }

        String[] cmdParts = CmdParser.parse(cmdLine);
        if (cmdParts.length < 2 || cmdParts.length > 3) {
            throw new IOException("Invalid " + APK_INSTALL_CMD + " command line, expected " + APK_INSTALL_CMD_EXAMPLE);
        }
        String cmd = cmdParts[0];
        if (!APK_INSTALL_CMD.equals(cmd)) {
            throw new IOException("Unexpected command, expecting " + APK_INSTALL_CMD_EXAMPLE);
        }

        String apkFile = cmdParts[1];
        boolean force = false;
        if (cmdParts.length == 3) {
            String forceString = cmdParts[2];
            if (forceString.startsWith(APK_INSTALL_FORCE)) {
                if (APK_INSTALL_FORCE.equals(forceString)) {
                    force = true;
                } else {
                    String[] forceParts = cmdParts[2].split("=");
                    if (forceParts.length != 2) {
                        throw new IOException("Unexpected force part of command, expecting " + APK_INSTALL_CMD_EXAMPLE);
                    }
                    force = Coerce.toBoolean(forceParts[1]);
                }
            } else {
                throw new IOException("Missing force part of command, expecting " + APK_INSTALL_CMD_EXAMPLE);
            }
        }
        return new Installer(apkFile, packageName, force, logger);
    }

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param packageName APK should contains that package
     * @param logger optional logger to log to
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    @Override
    public void installAPK(@NonNull String apkPath, @NonNull String packageName, boolean force,
                           @Nullable Logger logger) throws IOException, InterruptedException {
        if (logger == null) {
            logger = classLogger;
        }

        logger.atDebug().log("Installing {} from {}", packageName, apkPath);

        // check for interruption
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().log("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        // get info about APK
        PackageInfo apkPackageInfo = getApkPackageInfo(apkPath);
        long apkVersionCode = getVersionCode(apkPackageInfo);
        logger.atDebug().log("APK contains package {} version {} versionCode {}", apkPackageInfo.packageName,
                apkPackageInfo.versionName, apkVersionCode);

        // check is APK provide required package
        if (!packageName.equals(apkPackageInfo.packageName)) {
            throw new IOException(String.format("APK provides package %s but %s expected",
                    apkPackageInfo.packageName, packageName));
        }

        // check for interruption
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().log("Refusing to install because the active thread is interrupted");
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
            if (apkVersionCode == installedVersionCode
                    && apkPackageInfo.versionName.equals(installedPackageInfo.versionName)) {
                logger.atDebug().log("Package {} with same version and versionCode is already installed",
                        packageName);
                if (!force) {
                    updateAPKInstalled(packageName, true);
                    return;
                }
                logger.atDebug().log("Force flag is set, reinstall package {}", packageName);
            }
        }

        // check for interruption
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().log("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        boolean uninstalled = false;
        // check is uninstall required
        if (installedVersionCode != -1 && apkVersionCode < installedVersionCode) {
            logger.atDebug().log("Uninstalling package {} first due to downgrade is required from {} to {}",
                    packageName, installedVersionCode, apkVersionCode);
            uninstallPackage(packageName, logger);
            updateAPKInstalled(packageName, false);
            uninstalled = true;
        }

        // check for interruption but only when package was not uninstalled
        if (!uninstalled && Thread.currentThread().isInterrupted()) {
            logger.atWarn().log("Refusing to install because the active thread is interrupted");
            throw new InterruptedException();
        }

        // finally install APK without checks
        installAPK(apkPath, packageName, lastUpdateTime, logger);
    }

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param packageName APK should contains that package
     * @param lastUpdateTime previous package last update time to check for installation
     * @param logger optional logger to log to
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    // TODO: android: rework with PackageInstaller
    private void installAPK(@NonNull String apkPath, @NonNull String packageName,
                            long lastUpdateTime, Logger logger)
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
        if (!isIntentResolvable(intent, context)) {
            throw new IOException("No activity to handle install APK intent");
        }
        context.startActivity(intent);

        while (true) {
            Thread.sleep(INSTALL_POLL_INTERVAL);

            // check is package has been (re)installed
            PackageInfo newPackageInfo = getInstalledPackageInfo(packageName);
            if (newPackageInfo != null && newPackageInfo.lastUpdateTime > lastUpdateTime) {
                updateAPKInstalled(packageName, true);
                logger.atDebug().log("Package {} successfully installed", packageName);
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
    public void uninstallPackage(@NonNull String packageName, @Nullable Logger logger)
            throws IOException, InterruptedException {
        if (logger == null) {
            logger = classLogger;
        }
        //  for simple implementation see https://android.googlesource.com/platform/development/+/master/samples/ApiDemos/src/com/example/android/apis/content/InstallApk.java

        // first check is package installed
        PackageInfo installedPackageInfo = getInstalledPackageInfo(packageName);
        if (installedPackageInfo == null) {
            logger.atDebug().log("Package {} doesn't installed, nothing to do", packageName);
            return;
        }

        Intent intent = new Intent(PACKAGE_UNINSTALL_STATUS_ACTION);
        Context context = contextProvider.getContext();
        intent.setPackage(context.getPackageName());

        String requestId = getRandomRequestId();
        intent.putExtra(EXTRA_REQUEST_ID, requestId);


        // prepare everything required by PackageInstaller
        /*
        Up until Build.VERSION_CODES.R, PendingIntents are assumed to be mutable by default,
        unless FLAG_IMMUTABLE is set. Starting with Build.VERSION_CODES.S, it will be required
        to explicitly specify the mutability of PendingIntents on creation with either
        (@link #FLAG_IMMUTABLE} or FLAG_MUTABLE.
         */
        int flag;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            flag = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        } else {
            flag = PendingIntent.FLAG_UPDATE_CURRENT;
        }
        PendingIntent sender = PendingIntent.getBroadcast(context, 0, intent, flag);
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        IntentSender statusReceiver = sender.getIntentSender();

        UninstallContext uninstallContext = new UninstallContext(logger);
        uninstallRequests.put(requestId, uninstallContext);
        try {
            synchronized (uninstallContext) {
                context.registerReceiver(receiver, getIntentFilter());
                packageInstaller.uninstall(packageName, statusReceiver);

                uninstallContext.wait();

                Integer status = uninstallContext.status;
                if (status == null) {
                    throw new IOException("Uninstall failed, status unknown");
                }

                if (status != PackageInstaller.STATUS_SUCCESS) {
                    if (uninstallContext.message != null) {
                        throw new IOException("Uninstall failed, status " + status + " message "
                                + uninstallContext.message);
                    } else {
                        throw new IOException("Uninstall failed, status " + status);
                    }
                }
            }
        } finally {
            context.unregisterReceiver(receiver);
            uninstallRequests.remove(requestId);
        }
    }

    private IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PACKAGE_UNINSTALL_STATUS_ACTION);
        return intentFilter;
    }

    /**
     * Handle result of uninstall.
     *
     * @param intent information about uninstall operation status.
     */
    private void handleUninstallResult(Intent intent) {
        Bundle extras = intent.getExtras();
        int status = extras.getInt(PackageInstaller.EXTRA_STATUS);
        String message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String packageName = extras.getString(EXTRA_PACKAGE_NAME);
        String requestId = extras.getString(EXTRA_REQUEST_ID);
        Logger logger = getLogger(requestId);
        // TODO: set status specific for package name instead of generic
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // This app isn't privileged, so the user has to confirm the uninstall.
                Intent confirmIntent = (Intent) extras.get(Intent.EXTRA_INTENT);
                confirmIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                logger.atDebug().log("Requesting uninstall of {} confirmation from user", packageName);
                Context context = contextProvider.getContext();
                if (isIntentResolvable(confirmIntent, context)) {
                    context.startActivity(confirmIntent);
                } else {
                    logger.atError().log("No Activity to handle uninstall APK confirmation");
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                logger.atDebug().log("Uninstalling of {} succeeded", packageName);
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
                logger.atError().log("Uninstalling of {} failed, status {} message {}",
                        packageName, status, message);
                break;
            default:
                setUninstallStatus(requestId, status, message);
                logger.atError().log("Unrecognized status received from installer when uninstall {} status {}",
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
        UninstallContext result = uninstallRequests.get(requestId);
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

    /**
     * Provide logger specific for request.
     * @param requestId id of request
     * @return logger
     */
    private Logger getLogger(String requestId) {
        UninstallContext uninstallContext = uninstallRequests.get(requestId);
        if (uninstallContext == null || uninstallContext.logger == null) {
            return classLogger;
        }
        return uninstallContext.logger;
    }

    /**
     * Set or reset APK installed flags in all version of component.
     *
     * @param componentName name of component equals to APK package
     * @param isAPKInstalled new APK installation state
     */
    private void updateAPKInstalled(String componentName, boolean isAPKInstalled) {
        Platform platform = Platform.getInstance();
        platform.updateAPKInstalled(componentName, isAPKInstalled);
    }

    /**
     * Check is Intent resolved to Activity.
     *
     * @param intent intent to check
     * @param context Application content
     * @return true if intent is resolved to Activity
     */
    private boolean isIntentResolvable(Intent intent, Context context) {
        boolean isResolved = false;
        PackageManager packageManager = context.getPackageManager();
        if (packageManager != null) {
            ResolveInfo resolveInfo = packageManager.resolveActivity(intent, MATCH_DEFAULT_ONLY);
            isResolved = resolveInfo != null;
        }

        return isResolved;
    }
}
