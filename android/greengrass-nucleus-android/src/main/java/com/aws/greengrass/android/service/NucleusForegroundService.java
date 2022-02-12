/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.lifecyclemanager.AndroidExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.nucleus.R;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPackageIdentifier;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;
import com.vdurmont.semver4j.Semver;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

import static android.content.Intent.ACTION_VIEW;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_START_ACTION;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_STOP_ACTION;
import static java.lang.Thread.NORM_PRIORITY;

public class NucleusForegroundService extends Service implements AndroidServiceLevelAPI {
    private static final String COMPONENT_STARTED = "com.aws.greengrass.COMPONENT_STARTED";
    private static final String COMPONENT_STOPPED = "com.aws.greengrass.COMPONENT_STOPPED";
    private static final String KEY_COMPONENT_PACKAGE = "KEY_PACKAGE";

    // FIXME: probably arch. mistake; avoid direct usage of Kernel, hande incoming statuses here when possible
    private Kernel kernel;

    // Logger instance, postpone creation until Nucleus did initialization
    private Logger logger = null;

    // Package installation part
    public static final String PACKAGE_ARCHIVE = "application/vnd.android.package-archive";
    public static final String PROVIDER = ".provider";

    // Package uninstallation part
    private static final String PACKAGE_UNINSTALL_STATUS_ACTION
            = "com.aws.greengrass.PACKAGE_UNINSTALL_STATUS";
    private static final String PACKAGE_NAME = "PackageName";
    private static final String REQUEST_ID = "RequestId";

    // in-process uninstall requests
    private ConcurrentMap<String, UninstallResult> uninstallRequests = new ConcurrentHashMap<>();


    /**
     * Store result of uninstall operation.
     */
    private class UninstallResult {
        private Integer status;
        private String message;
    }

    // Service exit status.
    public int exitStatus = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                // FIXME: check also componentPackage to avoid stopping Nucleus on each stop command ?
                if (DEFAULT_STOP_ACTION.equals(action)) {
                    stopForegroundService();
                } else if (PACKAGE_UNINSTALL_STATUS_ACTION.equals(action)) {
                    uninstallIntentHandler(intent);
                } else if (action != null) {
                    // TODO: read also completion code when STOPPED
                    String componentPackage = intent.getStringExtra(KEY_COMPONENT_PACKAGE);
                    if (!TextUtils.isEmpty(componentPackage)) {
                        handleComponentResponses(action, componentPackage);
                    }
                }
            } catch (Throwable e) {
                logError("Error while processing incoming intent in BroadcastReceiver", e);
            }
        }
    };

    private final Thread nucleusThread = new Thread(() -> {
        try {

            File dir = getFilesDir();

            // build greengrass v2 path and create it
            File greengrass = new File(dir, "greengrass");
            File greengrassV2 = new File(greengrass, "v2");
            greengrassV2.mkdirs();

            // set required properties
            System.setProperty("log.store", "FILE");
            System.setProperty("root", greengrassV2.getAbsolutePath());
            System.setProperty("ipc.socket.port", String.valueOf(DEFAULT_PORT_NUMBER));
            final String[] fakeArgs = {"--setup-system-service", "false"};

            // FIXME: remove first call when got rid of onNewIntent
            ((AndroidPlatform)Platform.getInstance()).setAndroidServiceLevelAPI(this);
            kernel = GreengrassSetup.main(fakeArgs);

// TEST_ONLY: experimental
uninstallPackage("aws.greengrass.android.app.example", -1);

            // time to create logger
            synchronized (this) {
                logger = LogManager.getLogger(getClass());
            }

            /* FIXME: Implement by right way */
            while (true) {
                Thread.sleep(30 * 1000);
            }
        } catch (InterruptedException e) {
            System.console().printf("Nucleus thread interrupted");
        } catch (Throwable e) {
            logError("Error while running Nucleus core main thread", e);
        }
    });

    /**
     *  Starting Nucleus as Android Foreground Service.
     *
     * @param context Context of android application.
     */
    public static void launch(@NonNull Context context) {
        startService(context, context.getPackageName(),
                NucleusForegroundService.class.getCanonicalName(), DEFAULT_START_ACTION);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(receiver, getComponentFilter());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null) {
                String action = intent.getAction();
                if (DEFAULT_START_ACTION.equals(action)) {
                    Notification notification = NotManager.notForService(this, getString(R.string.not_title));
                    startForeground(SERVICE_NOT_ID, notification);
                    if (!nucleusThread.isAlive()) {
                        nucleusThread.setPriority(NORM_PRIORITY);
                        nucleusThread.start();
                    }
                }
            }
        } catch (Throwable e) {
            logError("Error while processing Foreground Service command", e);
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        logDebug("onDestroy");
        // TODO: check for STOP request or Android termination
        // FIXME: android:
        //  1. integrate component's library.
        //  2. Here we must stop Nucleus core and Nucleus init thread and send responses to initiator
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void stopForegroundService() {
        logDebug("Got STOP command, shutting down Foreground Service");
        // TODO: get status of service
        // FIXME: android: 
        //  1. integrate component's library.
        //  2. Here we must stop Nucleus core and Nucleus init thread and send responses to initiator
        stopForeground(true);
        stopSelf();
    }

    private IntentFilter getComponentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DEFAULT_STOP_ACTION);
        intentFilter.addAction(COMPONENT_STARTED);
        intentFilter.addAction(COMPONENT_STOPPED);
        intentFilter.addAction(PACKAGE_UNINSTALL_STATUS_ACTION);
        return intentFilter;
    }

    private void handleComponentResponses(String action, String sourcePackage)
            throws ServiceLoadException {
        logDebug("Handling component response action {} sourcePackage {}", action, sourcePackage);
        if (kernel != null) {
            GreengrassService component;
            component = kernel.locate(sourcePackage);
            if (component instanceof AndroidExternalService) {
                AndroidExternalService androidComponent = (AndroidExternalService) component;
                switch (action) {
                    case COMPONENT_STARTED:
                        androidComponent.componentRunning();
                        break;
                    case COMPONENT_STOPPED:
                        androidComponent.componentFinished();
                        break;
                    default:;
                }
            }
        }
    }

    // Implementation methods of AndroidComponentManager
    // TODO: move to 2nd library
    /**
     * Start Android component as Activity.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the Activity.
     * @param action Action of Intent to send.
     * @throws IOException on errors
     */
    @Override
    public void startActivity(@NonNull String packageName, @NonNull String className,
            @NonNull String action) throws RuntimeException {
        Intent intent = new Intent();
        ComponentName componentName = new ComponentName(packageName, className);
        intent.setComponent(componentName);
        intent.setAction(action);
        Application app = getApplication();
        if (intent.resolveActivityInfo(app.getPackageManager(), 0) != null) {
            NotManager.notForActivityComponent(app, intent,
                    app.getString(com.aws.greengrass.nucleus.R.string.click_to_start_component));
        } else {
            throw new RuntimeException("Could not find Activity by package " + packageName + " class " + className);
        }
    }

    /**
     * Stop Android component started as Activity.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the Activity.
     * @param action Action of Intent to send.
     * @throws IOException on errors
     */
    @Override
    public void stopActivity(String packageName, @NonNull String className, @NonNull String action)
            throws RuntimeException {
        Intent intent = new Intent();
        ComponentName componentName = new ComponentName(packageName, className);
        intent.setComponent(componentName);
        intent.setAction(action);
        intent.setPackage(packageName);
        Application app = getApplication();
        if (intent.resolveActivityInfo(app.getPackageManager(), 0) != null) {
            app.sendBroadcast(intent);
        } else {
            throw new RuntimeException("Could not find Activity by package " + packageName + " class " + className);
        }
    }

    /**
     * Initiate starting Android component as Foreground Service.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send
     * @throws IOException on errors
     */
    @Override
    public void startService(@NonNull String packageName, @NonNull String className,
            @NonNull String action) throws RuntimeException {
        startService(getApplication(), packageName, className, action);
    }

    /**
     * Implementation of starting Android component as Foreground Service.
     *
     * @param context Context of Activity or Foreground service
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send
     * @throws RuntimeException on errors
     */
    public static void startService(@NonNull Context context, @NonNull String packageName, @NonNull String className,
            @NonNull String action) throws RuntimeException {
        Intent intent = new Intent();
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(action);
        intent.setComponent(new ComponentName(packageName, className));
        List<ResolveInfo> matches = context.getPackageManager().queryIntentServices(intent, 0);
        if (matches.size() == 1) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            handleIntentResolutionError(matches, packageName, className);
        }
    }

    /**
     * Initiate stopping Android component was started as Foreground Service.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send.
     * @throws IOException on errors
     */
    @Override
    public void stopService(@NonNull String packageName, @NonNull String className,
            @NonNull String action) throws RuntimeException {
        Application app = getApplication();
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, className));
        intent.setAction(action);
        intent.setPackage(packageName);
        List<ResolveInfo> matches = app.getPackageManager().queryIntentServices(intent, 0);
        if (matches.size() == 1) {
            app.sendBroadcast(intent);
        } else {
            handleIntentResolutionError(matches, packageName, className);
        }
    }

    private static void handleIntentResolutionError(List<ResolveInfo> matches,
                                                    @NonNull String packageName,
                                                    @NonNull String className) throws RuntimeException {
        if (matches.size() == 0) {
            throw new RuntimeException("Service with package " + packageName + " and class "
                    + className + " couldn't found");
        } else {
            throw new RuntimeException("Ambiguity in service with package " + packageName + " and class "
                    + className + " found " + matches.size() + " matches");
        }
    }

    // Implementation of methods from AndroidUserId interface
    // TODO: remove
    /**
     * Get user id of current user.
     *
     * @return uid of current user.
     */
    @Override
    public long getUID() {
        ActivityManager activityManager = (ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo processInfo = activityManager.getRunningAppProcesses().get(0);
        return processInfo.uid;
    }

    // Implementation of methods from AndroidPackageManager interface.
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

        Intent intent = new Intent(PACKAGE_UNINSTALL_STATUS_ACTION);
        intent.setPackage(getPackageName());
        intent.putExtra(PACKAGE_NAME, packageName);
        String requestId = getRandomRequestId();
        intent.putExtra(REQUEST_ID, requestId);


        // prepare everything required by PackageInstaller
        //PendingIntent sender = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent sender = PendingIntent.getBroadcast(this, 0, intent, 0);
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

    /**
     * Handle result of uninstall.
     *
     * @param intent information about uninstall operation status.
     */
    private void uninstallIntentHandler(Intent intent) {
        Bundle extras = intent.getExtras();
        int status = extras.getInt(PackageInstaller.EXTRA_STATUS);
        String message = extras.getString(PackageInstaller.EXTRA_STATUS_MESSAGE);
        String packageName = extras.getString(PACKAGE_NAME);
        int requestId = extras.getInt(REQUEST_ID);
        // TODO: set status specific for package name instead of generic
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                // This app isn't privileged, so the user has to confirm the uninstall.
                Intent confirmIntent = (Intent) extras.get(Intent.EXTRA_INTENT);
                logDebug("Requesting uninstall of " + packageName + " confirmation from user");
                startActivity(confirmIntent);
                break;
            case PackageInstaller.STATUS_SUCCESS:
                logDebug("Uninstalling of " + packageName + " succeeded");
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
                logError("Uninstalling of " + packageName + " failed, status "
                        + status + " message " + message);
                break;
            default:
                setUninstallStatus(requestId, status, message);
                logError("Unrecognized status received from installer when uninstall "
                        + packageName + " status " + status);
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
     * Send message to logs with debug log level.
     * @param s message
     * @param objects related objects
     */
    private void logDebug(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.debug(s, objects);
        }
    }

    /**
     * Send message to logs with debug log level.
     * @param s message
     * @param objects related objects
     */
    private void logError(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.error(s, objects);
        }
    }

    // Implementation of methods from AndroidServiceLevelAPI interface
    @Override
    public void terminate(int status) {
        exitStatus = status;
        // TODO: android: add service termination
    }

}
