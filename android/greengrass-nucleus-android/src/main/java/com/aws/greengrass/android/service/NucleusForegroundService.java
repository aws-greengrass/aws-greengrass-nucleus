/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

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
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.component.service.GreengrassComponentService;
import com.aws.greengrass.android.managers.AndroidBasePackageManager;
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
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import java.io.File;
import java.util.List;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_COMPONENT_STARTED;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_COMPONENT_STOPPED;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_COMPONENT_PACKAGE;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_START_ACTION;

public class NucleusForegroundService extends GreengrassComponentService implements AndroidServiceLevelAPI, AndroidContextProvider {

    private Thread thread;

    // FIXME: probably arch. mistake; avoid direct usage of Kernel, handle incoming statuses here when possible
    private Kernel kernel;

    /* Logger here can't be static due to require execute initialize() before which is use
        getFilesDir() to detect Nucleus working directory
     */
    private Logger logger;
    private AndroidBasePackageManager packageManager;

    // Service exit status.
    public int exitCode = 0;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                String action = intent.getAction();
                if (action != null) {
                    String componentPackage = intent.getStringExtra(EXTRA_COMPONENT_PACKAGE);
                    if (!TextUtils.isEmpty(componentPackage)) {
                        // do not handle responses from Nucleus itself
                        if (!componentPackage.equals((getPackageName()))) {
                            // TODO: read also completion code when STOPPED
                            handleComponentResponses(action, componentPackage);
                        }
                    }
                }
            } catch (Throwable e) {
                logger.atError().setCause(e)
                        .log("Error while processing incoming intent in BroadcastReceiver");
            }
        }
    };

    @Override
    public int doWork() {
        try {
            thread = Thread.currentThread();
            final String[] fakeArgs = {"--setup-system-service", "false"};
            ((AndroidPlatform) Platform.getInstance()).setAndroidServiceLevelAPIs(this, packageManager);
            kernel = GreengrassSetup.main(fakeArgs);

            // wait for Thread.interrupt() call
            wait();
        } catch (InterruptedException e) {
            logger.atInfo("Nucleus thread interrupted");
        } catch (Throwable e) {
            logger.atError().setCause(e).log("Error while running Nucleus core main thread");
            return EXIT_CODE_FAILED;
        }
        return exitCode;
    }

    /**
     *  Starting Nucleus as Android Foreground Service.
     *
     * @param context Context of android application.
     * @throws RuntimeException on errors
     */
    public static void launch(@NonNull Context context) throws RuntimeException {
        startService(context, context.getPackageName(),
                NucleusForegroundService.class.getCanonicalName(), DEFAULT_START_ACTION);
    }

    /**
     * Initialize logger part.
     */
    private void initialize() {
        // 1. obtain greengrass working directory
        File dir = getFilesDir();

        // build greengrass v2 path and create it
        File greengrass = new File(dir, "greengrass");
        File greengrassV2 = new File(greengrass, "v2");
        greengrassV2.mkdirs();

        // set required properties
        System.setProperty("log.store", "FILE");
        System.setProperty("root", greengrassV2.getAbsolutePath());

        // 2. create logger and APK manager
        logger = LogManager.getLogger(getClass());
        packageManager = new AndroidBasePackageManager(this);

        // FIXME: remove that code when provide field in config file
        System.setProperty("ipc.socket.port", String.valueOf(DEFAULT_PORT_NUMBER));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initialize();
        registerReceiver(receiver, getIntentFilter());
    }

    @Override
    public void onDestroy() {
        logger.atDebug().log("onDestroy");
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    @Override
    public Notification getNotification() {
        return NotManager.notForService(this, getString(R.string.not_title));
    }

    @Override
    public int getNotificationId() {
        return SERVICE_NOT_ID;
    }

    private IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_COMPONENT_STARTED);
        intentFilter.addAction(ACTION_COMPONENT_STOPPED);
        return intentFilter;
    }

    private void handleComponentResponses(String action, String sourcePackage)
            throws ServiceLoadException {
        logger.atDebug().log("Handling component response action {} sourcePackage {}", action, sourcePackage);
        if (kernel != null) {
            GreengrassService component;
            component = kernel.locate(sourcePackage);
            if (component instanceof AndroidExternalService) {
                AndroidExternalService androidComponent = (AndroidExternalService) component;
                switch (action) {
                    case ACTION_COMPONENT_STARTED:
                        androidComponent.componentRunning();
                        break;
                    case ACTION_COMPONENT_STOPPED:
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
     * @throws RuntimeException on errors
     */
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

    /**
     * Stop Android component started as Activity.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the Activity.
     * @param action Action of Intent to send.
     * @throws RuntimeException on errors
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
     * @throws RuntimeException on errors
     */
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
            handleIntentResolutionError(matches, packageName, className);
        }
    }

    /**
     * Initiate stopping Android component was started as Foreground Service.
     *
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send.
     * @throws RuntimeException on errors
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
        exitCode = status;
        thread.interrupt();
    }
}
