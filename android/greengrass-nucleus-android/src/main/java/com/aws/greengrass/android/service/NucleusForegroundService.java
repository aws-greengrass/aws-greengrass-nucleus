/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_START_ACTION;

import android.app.ActivityManager;
import android.app.Application;
import android.app.Notification;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

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
import com.aws.greengrass.util.platforms.android.AndroidAppLevelAPI;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import java.io.File;
import java.util.List;

import aws.greengrass.android.component.service.GreengrassComponentService;

public class NucleusForegroundService extends GreengrassComponentService implements AndroidServiceLevelAPI {

    private static final String COMPONENT_STARTED = "com.aws.greengrass.COMPONENT_STARTED";
    private static final String COMPONENT_STOPPED = "com.aws.greengrass.COMPONENT_STOPPED";
    public static final String PACKAGE_UNINSTALL_STATUS_ACTION = "com.aws.greengrass.PACKAGE_UNINSTALL_STATUS";
    private static final String KEY_COMPONENT_PACKAGE = "KEY_PACKAGE";

    // Logger instance, postpone creation until Nucleus did initialization
    private Logger logger = null;

    // FIXME: probably arch. mistake; avoid direct usage of Kernel, hande incoming statuses here when possible
    private Kernel kernel;

    // TODO: remove this reference when got rid of onNewIntent()
    private static AndroidAppLevelAPI androidAppLevelAPI;

    // Service exit status.
    public int exitStatus = -1;

    private final BroadcastReceiver additionalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                String componentPackage = intent.getStringExtra(KEY_COMPONENT_PACKAGE);
                if (!TextUtils.isEmpty(action)
                        && !TextUtils.isEmpty(componentPackage)) {
                    if (componentPackage.equals(getPackageName())) {
                        return;
                    }
                    if (!TextUtils.isEmpty(componentPackage)) {
                        handleComponentResponse(action, componentPackage);
                    }
                }
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    };

    /**
     * Starting Nucleus as Android Foreground Service.
     *
     * @param context          Context of android application.
     * @param androidAppLvlAPI Application Level API. TODO: remove when move uninstall to that layer.
     */
    public static void launch(@NonNull Context context,
                              @NonNull AndroidAppLevelAPI androidAppLvlAPI) {
        androidAppLevelAPI = androidAppLvlAPI;
        startService(context, context.getPackageName(),
                NucleusForegroundService.class.getCanonicalName(), DEFAULT_START_ACTION);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(additionalReceiver, getIntentFilter());
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(additionalReceiver);
        super.onDestroy();
    }

    @Override
    public void prepareToStartComponent() {

    }

    @Override
    public void prepareToStopComponent() {
    }

    @Override
    public int doWork() {
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
            ((AndroidPlatform) Platform.getInstance()).setAndroidAppLevelAPI(androidAppLevelAPI);
            ((AndroidPlatform) Platform.getInstance()).setAndroidServiceLevelAPI(this);
            kernel = GreengrassSetup.main(fakeArgs);

            // time to create logger
            synchronized (this) {
                logger = LogManager.getLogger(getClass());
            }

            /* FIXME: Implement by right way */
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(30 * 1000);
            }
        } catch (InterruptedException e) {
            System.console().printf("Nucleus thread interrupted");
        } catch (Throwable e) {
            error("Error while running Nucleus core main thread", e);
        }
        return 0;
    }

    @Override
    public Notification getNotification() {
        return NotManager.notForService(this, getString(R.string.not_title));
    }

    @Override
    public int getNotificationId() {
        return SERVICE_NOT_ID;
    }

    // Implementation methods of AndroidComponentManager
    // TODO: move to 2nd library
    @Override
    public void startActivity(@NonNull String packageName, @NonNull String className, @NonNull String action) throws RuntimeException {
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

    @Override
    public void startService(@NonNull String packageName, @NonNull String className,
                             @NonNull String action) throws RuntimeException {
        startService(getApplication(), packageName, className, action);
    }

    /**
     * Implementation of starting Android component as Foreground Service.
     *
     * @param context     Context of Activity or Foreground service
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param action      Action of Intent to send
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
            handleResolutionError(matches, packageName, className);
        }
    }

    private static void handleResolutionError(List<ResolveInfo> matches,
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
            handleResolutionError(matches, packageName, className);
        }
    }

    // Implementation of methods from AndroidServiceLevelAPI interface
    @Override
    public void terminate(int status) {
        exitStatus = status;
        // TODO: android: add service termination
    }

    // Implementation of methods from AndroidUserId interface

    /**
     * Get user id of current user.
     *
     * @return uid of current user.
     */
    @Override
    public long getUID() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.RunningAppProcessInfo processInfo = activityManager.getRunningAppProcesses().get(0);
        return processInfo.uid;
    }

    private void debug(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.debug(s, objects);
        }
    }

    private void error(String s, Object... objects) {
        Logger localLogger;
        synchronized (this) {
            localLogger = logger;
        }
        if (localLogger != null) {
            localLogger.error(s, objects);
        }
    }

    private IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(COMPONENT_STARTED);
        intentFilter.addAction(COMPONENT_STOPPED);
        intentFilter.addAction(PACKAGE_UNINSTALL_STATUS_ACTION);
        return intentFilter;
    }

    private void handleComponentResponse(String action, String sourcePackage)
            throws ServiceLoadException {
        debug("Handling component response action {} sourcePackage {}", action, sourcePackage);
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
                    case PACKAGE_UNINSTALL_STATUS_ACTION:
                        // FIXME: need to handle it
                        break;
                }
            }
        }
    }
}
