/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import com.aws.greengrass.android.AndroidContextProvider;
import com.aws.greengrass.android.component.core.GreengrassComponentService;
import com.aws.greengrass.android.managers.AndroidBaseComponentManager;
import com.aws.greengrass.android.managers.AndroidBasePackageManager;
import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.nucleus.R;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidComponentManager;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import java.util.HashMap;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;

public class NucleusForegroundService extends GreengrassComponentService
        implements AndroidServiceLevelAPI, AndroidContextProvider {
    private static final Integer NUCLEUS_RESTART_DELAY_MS = 3000;
    private static final Integer NUCLEUS_RESTART_INTENT_ID = 0;

    /** Nucleus service initialization thread. */
    private Thread myThread;

    /** Service exit code. */
    public int exitCode = EXIT_CODE_SUCCESS;

    // initialized in onCreate()
    private AndroidBasePackageManager packageManager;

    // initialized by launch()
    private static Logger logger;
    private static AndroidComponentManager componentManager;
    private static final String authToken = Utils.generateRandomString(16).toUpperCase();

    @Override
    public int doWork() {
        Kernel kernel = null;
        try {
            // save current thread object for future references
            myThread = Thread.currentThread();

            AndroidPlatform platform = (AndroidPlatform) Platform.getInstance();
            platform.setAndroidAPIs(this, packageManager, componentManager);

            ProvisionManager provisionManager = BaseProvisionManager.getInstance(getFilesDir());
            final String[] nucleusArguments = provisionManager.prepareArguments();
            kernel = GreengrassSetup.main(nucleusArguments);

            // Clear system properties
            provisionManager.clearSystemProperties();

            if (!provisionManager.isProvisioned()) {
                provisionManager.writeConfig(kernel);
            }

            // waiting for Thread.interrupt() call
            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            logger.atInfo().kv("exitCode", exitCode).log("Nucleus thread terminated");
        } catch (Throwable e) {
            logger.atError().setCause(e).log("Error while running Nucleus core main thread");
        } finally {
            if (kernel != null) {
                kernel.shutdown();
            }
        }
        return exitCode;
    }

    /**
     * Starting Nucleus as Android Foreground Service.
     *
     * @param context context of application
     */
    public static synchronized void launch(Context context) {
        if (logger == null) {
            logger = LogHelper.getLogger(context.getFilesDir(), NucleusForegroundService.class);
        }
        if (componentManager == null) {
            final AndroidContextProvider androidContextProvider = new AndroidContextProvider() {
                // FIXME: get return reference to dead context ?
                @Override
                public Context getContext() {
                    return context;
                }
            };

            componentManager = new AndroidBaseComponentManager(androidContextProvider);
        }

        HashMap<String, String> environment = new HashMap<String, String>();
        environment.put("SVCUID", authToken);

        Thread startupThread = new Thread(() -> {
            try {
                componentManager.startService(context.getPackageName(),
                        NucleusForegroundService.class.getCanonicalName(),
                        ACTION_START_COMPONENT,
                        null,
                        environment,
                        logger,
                        s -> {
                            String ss = s.toString().trim();
                            logger.atInfo().setEventType("stdout").log(ss);
                        },
                        s -> {
                            String ss = s.toString().trim();
                            logger.atWarn().setEventType("stderr").log(ss);
                        });
            } catch (Throwable e) {
                logger.atError().setCause(e).log("Couldn't start Nucleus service");
            }
        });
        startupThread.setDaemon(true);
        startupThread.start();
    }

    /**
     * Stop Nucleus as Android Foreground Service.
     *
     * @param context context of application
     */
    public static void finish(Context context) {
        if (componentManager != null) {
            try {
                componentManager.stopService(context.getPackageName(),
                        NucleusForegroundService.class.getCanonicalName(),
                        logger);
            } catch (Throwable e) {
                logger.atError().setCause(e).log("Couldn't stop Nucleus service");
            }
        }
    }

    private void scheduleRestart() {
        Intent intent = new Intent();
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(ACTION_START_COMPONENT);
        intent.setComponent(
                new ComponentName(this.getPackageName(), NucleusForegroundService.class.getCanonicalName())
        );

        PendingIntent pendingIntent = PendingIntent.getService(this,
                NUCLEUS_RESTART_INTENT_ID,
                intent,
                PendingIntent.FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE);
        AlarmManager mgr = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + NUCLEUS_RESTART_DELAY_MS, pendingIntent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        packageManager = new AndroidBasePackageManager(this);

        // FIXME: remove that code when provide field in config file
        System.setProperty("ipc.socket.port", String.valueOf(DEFAULT_PORT_NUMBER));
    }

    @Override
    public void onDestroy() {
        logger.atDebug().log("onDestroy");
        if (exitCode == REQUEST_RESTART) {
            scheduleRestart();
        }
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

    // Implementation of AndroidServiceLevelAPI interface
    @Override
    public void terminate(int status) {
        exitCode = status;
        myThread.interrupt();
    }
}
