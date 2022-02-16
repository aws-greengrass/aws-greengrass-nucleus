/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.managers.AndroidBasePackageManager;
import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.android.utils.LazyLogger;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.lifecyclemanager.AndroidExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.nucleus.R;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.managers.AndroidBasePackageManager.PACKAGE_UNINSTALL_STATUS_ACTION;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_START_ACTION;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_STOP_ACTION;
import static java.lang.Thread.NORM_PRIORITY;

public class NucleusForegroundService extends Service implements AndroidServiceLevelAPI, AndroidContextProvider {
    private static final String COMPONENT_STARTED = "com.aws.greengrass.COMPONENT_STARTED";
    private static final String COMPONENT_STOPPED = "com.aws.greengrass.COMPONENT_STOPPED";
    private static final String KEY_COMPONENT_PACKAGE = "KEY_PACKAGE";

    // FIXME: probably arch. mistake; avoid direct usage of Kernel, handle incoming statuses here when possible
    private Kernel kernel;

    private LazyLogger logger = new LazyLogger();
    private AndroidBasePackageManager packageManager = new AndroidBasePackageManager(this);

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
                    packageManager.handlerUninstallResult(intent);
                } else if (action != null) {
                    // TODO: read also completion code when STOPPED
                    String componentPackage = intent.getStringExtra(KEY_COMPONENT_PACKAGE);
                    if (!TextUtils.isEmpty(componentPackage)) {
                        handleComponentResponses(action, componentPackage);
                    }
                }
            } catch (Throwable e) {
                logger.logError("Error while processing incoming intent in BroadcastReceiver", e);
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
            ((AndroidPlatform)Platform.getInstance()).setAndroidServiceLevelAPIs(this, packageManager);
            kernel = GreengrassSetup.main(fakeArgs);

            // time to create loggers
            logger.initLogger(getClass());
            packageManager.initLogger(packageManager.getClass());

            /* FIXME: Implement by right way */
            while (true) {
                Thread.sleep(30 * 1000);
            }
        } catch (InterruptedException e) {
            System.console().printf("Nucleus thread interrupted");
        } catch (Throwable e) {
            logger.logError("Error while running Nucleus core main thread", e);
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
            logger.logError("Error while processing Foreground Service command", e);
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
        logger.logDebug("onDestroy");
        // TODO: check for STOP request or Android termination
        // FIXME: android:
        //  1. integrate component's library.
        //  2. Here we must stop Nucleus core and Nucleus init thread and send responses to initiator
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void stopForegroundService() {
        logger.logDebug("Got STOP command, shutting down Foreground Service");
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
        logger.logDebug("Handling component response action {} sourcePackage {}", action, sourcePackage);
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
                                                    @NonNull String className)
            throws RuntimeException {
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

    // Implementation of AndroidContextProvider interface.
    /**
     * Get an Android Context.
     *
     * @return Android context object
     */
    @Override
    public Context getContext() {
        return getApplicationContext();
    }

    // Implementation of methods from AndroidServiceLevelAPI interface
    @Override
    public void terminate(int status) {
        exitStatus = status;
        // TODO: android: add service termination
    }

}
