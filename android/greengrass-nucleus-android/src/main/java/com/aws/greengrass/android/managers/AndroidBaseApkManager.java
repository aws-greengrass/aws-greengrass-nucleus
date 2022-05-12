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
import android.os.Build;
import android.os.Bundle;

import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPackageIdentifier;
import com.aws.greengrass.util.platforms.android.AndroidApkManager;
import com.aws.greengrass.util.platforms.android.AndroidVirtualCmdExecution;
import com.vdurmont.semver4j.Semver;
import lombok.AllArgsConstructor;
import lombok.NonNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.jar.JarException;
import javax.annotation.Nullable;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.pm.PackageInstaller.EXTRA_PACKAGE_NAME;
import static android.content.pm.PackageManager.MATCH_DEFAULT_ONLY;

/**
 * Basic implementation of AndroidPackageManager interface.
 */
public class AndroidBaseApkManager implements AndroidApkManager {
    // Package install part.
    public static final String APK_INSTALL_CMD = "#install_package";
    private static final String APK_INSTALL_CMD_EXAMPLE = "#install_package path_to.apk [force[=false]]";
    private static final String APK_INSTALL_FORCE = "force";

    // Package install part.
    private static final String PACKAGE_INSTALL_STATUS_ACTION
            = "com.aws.greengrass.PACKAGE_INSTALL_STATUS";
    // Package uninstall part.
    private static final String PACKAGE_UNINSTALL_STATUS_ACTION
            = "com.aws.greengrass.PACKAGE_UNINSTALL_STATUS";
    private static final String EXTRA_REQUEST_ID = "RequestId";

    // that Logger is backed to greengrass.log
    private final Logger classLogger;

    // In-process install and uninstall requests.
    private final ConcurrentMap<String, OperationContext> pendingRequests = new ConcurrentHashMap<>();

    // Reference to context provider
    private final AndroidContextProvider contextProvider;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (PACKAGE_INSTALL_STATUS_ACTION.equals(action)) {
                    handleOperationResult(intent, "install");
                } else if (PACKAGE_UNINSTALL_STATUS_ACTION.equals(action)) {
                    handleOperationResult(intent, "uninstall");
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
    private class OperationContext {
        private Integer status;
        private String message;
        private Logger logger;

        OperationContext(Logger logger) {
            this.logger = logger;
        }
    }


    /**
     * Creates instance of AndroidBasePackageManager.
     *
     * @param contextProvider reference to context getter
     */
    public AndroidBaseApkManager(AndroidContextProvider contextProvider) {
        classLogger = LogHelper.getLogger(contextProvider.getContext().getFilesDir(), getClass());
        this.contextProvider = contextProvider;
        contextProvider.getContext().registerReceiver(receiver, getIntentFilter());
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
    public synchronized void installAPK(@NonNull String apkPath, @NonNull String packageName, boolean force,
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
        PackageInfo installedPackageInfo = getInstalledPackageInfo(packageName);
        if (installedPackageInfo != null) {
            installedVersionCode = getVersionCode(installedPackageInfo);
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
        installAPK(apkPath, packageName, logger);
    }

    /**
     * Install APK file.
     *
     * @param apkPath   path to APK file
     * @param packageName APK contains that package
     * @param logger optional logger to log to
     * @throws IOException      on errors
     * @throws InterruptedException when thread has been interrupted
     */
    private void installAPK(@NonNull String apkPath, @NonNull String packageName, Logger logger)
            throws IOException, InterruptedException {

        Intent intent = new Intent(PACKAGE_INSTALL_STATUS_ACTION);
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
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flag);
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        IntentSender statusReceiver = pendingIntent.getIntentSender();

        OperationContext installContext = new OperationContext(logger);
        pendingRequests.put(requestId, installContext);

        PackageInstaller.Session session = null;
        try {
            synchronized (installContext) {
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int sessionId = packageInstaller.createSession(params);
                session = packageInstaller.openSession(sessionId);

                addApkToInstallSession(apkPath, packageName, session);

                // Commit the session (this will start the installation workflow).
                session.commit(statusReceiver);
                session = null;

                installContext.wait();

                Integer status = installContext.status;
                if (status == null) {
                    throw new IOException("Install failed, status unknown");
                }

                if (status != PackageInstaller.STATUS_SUCCESS) {
                    if (installContext.message != null) {
                        throw new IOException("Install failed, status " + status + " message "
                                + installContext.message);
                    } else {
                        throw new IOException("Install failed, status " + status);
                    }
                }
            }
        } finally {
            if (session != null) {
                session.abandon();
            }
            pendingRequests.remove(requestId);
        }
    }

    /**
     * Send APK file to sessions.
     *
     * @param apkPath name of APK to install
     * @param packageName APK contains that package
     * @param session sessions of package installer
     * @throws IOException on io errors
     */
    private void addApkToInstallSession(@NonNull String apkPath, @NonNull String packageName,
                                        PackageInstaller.Session session)
            throws IOException {
        File file = new File(apkPath);
        long fileSize = file.length();

        // It's recommended to pass the file size to openWrite(). Otherwise installation may fail
        // if the disk is almost full.
        try (OutputStream packageInSession = session.openWrite(packageName, 0, fileSize);
             InputStream is = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = is.read(buffer)) >= 0) {
                packageInSession.write(buffer, 0, n);
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
    public synchronized void uninstallPackage(@NonNull String packageName, @Nullable Logger logger)
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
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flag);
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        IntentSender statusReceiver = pendingIntent.getIntentSender();

        OperationContext uninstallContext = new OperationContext(logger);
        pendingRequests.put(requestId, uninstallContext);
        try {
            synchronized (uninstallContext) {
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
            pendingRequests.remove(requestId);
        }
    }

    private IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PACKAGE_INSTALL_STATUS_ACTION);
        intentFilter.addAction(PACKAGE_UNINSTALL_STATUS_ACTION);
        return intentFilter;
    }

    /**
     * Handle result of install or uninstall.
     *
     * @param intent information about install or uninstall operation status.
     * @param operation "install" or "uninstall"
     */
    private void handleOperationResult(Intent intent, String operation) {
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
                logger.atDebug().log("Requesting {} of {} confirmation from user", operation, packageName);
                Context context = contextProvider.getContext();
                if (isIntentResolvable(confirmIntent, context)) {
                    context.startActivity(confirmIntent);
                } else {
                    logger.atError().log("No Activity to handle {} APK confirmation", operation);
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                logger.atDebug().log("{} of {} succeeded", operation, packageName);
                setOperationStatus(requestId, status, message);
                break;
            case PackageInstaller.STATUS_FAILURE:
            case PackageInstaller.STATUS_FAILURE_ABORTED:
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
            case PackageInstaller.STATUS_FAILURE_INVALID:
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                setOperationStatus(requestId, status, message);
                logger.atError().log("{} of {} failed, status {} message {}",
                        operation, packageName, status, message);
                break;
            default:
                setOperationStatus(requestId, status, message);
                logger.atError().log("Unrecognized status received from PackageInstaller when {}} {} status {}",
                        operation, packageName, status);
        }
    }

    /**
     * Save install or uninstall status and notify waiting threads.
     *
     * @param requestId  Id of request
     * @param status status of removal
     * @param message message from installer
     */
    private void setOperationStatus(String requestId, int status, String message) {
        OperationContext result = pendingRequests.get(requestId);
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
        OperationContext uninstallContext = pendingRequests.get(requestId);
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
