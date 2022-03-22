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
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;

public class DefaultGreengrassComponentService extends GreengrassComponentService
        implements AndroidServiceLevelAPI, AndroidContextProvider {
    private static final Integer NUCLEUS_RESTART_DELAY_MS = 3000;
    private static final Integer NUCLEUS_RESTART_INTENT_ID = 0;
    private static final String EXTRA_START_ATTEMPTS_COUNTER = "START_ATTEMPTS_COUNTER";
    private static final int NUCLEUS_START_ATTEMPTS_LIMIT = 3;

    /** Nucleus service initialization thread. */
    private Thread myThread;
    /** Counter for Nucleus startup attempts. */
    private int startAttemptsCounter = NUCLEUS_START_ATTEMPTS_LIMIT;
    /** Service exit code. */
    public int exitCode = EXIT_CODE_FAILED; // assume failure by default
    /** Indicator that Nucleus execution resulted in an error. */
    private boolean errorDetected = true; // assume failure by default

    // initialized in onCreate()
    private AndroidBasePackageManager packageManager;

    // initialized by launch()
    private static Logger logger;
    private static AndroidComponentManager componentManager;
    private static final String authToken = Utils.generateRandomString(16).toUpperCase();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            if (intent != null && ACTION_START_COMPONENT.equals(intent.getAction())) {
                startAttemptsCounter = intent.getIntExtra(EXTRA_START_ATTEMPTS_COUNTER, 1);
                logger.atDebug()
                        .log("Start attempts counter extracted from the startup intent. Counter value: %d",
                                startAttemptsCounter);
                if (startAttemptsCounter < 0 || startAttemptsCounter > NUCLEUS_START_ATTEMPTS_LIMIT) {
                    startAttemptsCounter = NUCLEUS_START_ATTEMPTS_LIMIT;
                    logger.atWarn()
                            .log("Start attempts counter value is not within the limits. Value saturated to %d",
                                    startAttemptsCounter);
                }
            } else {
                // There's no intent or the intent is missing start attempts counter.
                startAttemptsCounter = 0;
                logger.atDebug("Startup attempts counter value is not present "
                        + "in the startup intent. Assuming default value");
            }
        } catch (Throwable e) {
            logger.atError().setCause(e).log("Fatal error at startup");

            // Abort startup by reporting not-sticky start
            stopSelf();
            return START_NOT_STICKY;
        }

        // Do normal startup if everything is fine
        startAttemptsCounter++;
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public int doWork() {
        if (startAttemptsCounter > NUCLEUS_START_ATTEMPTS_LIMIT) {
            // This is the protection from malformed intents
            logger.atError()
                    .log("Startup attempts counter is over the limit. "
                            + "Probably, startup intent is malformed. Startup aborted");
            return EXIT_CODE_FAILED;
        } else {
            Kernel kernel = null;
            try {
                // save current thread object for future references
                myThread = Thread.currentThread();

                AndroidPlatform platform = (AndroidPlatform) Platform.getInstance();
                platform.setAndroidAPIs(this, packageManager, componentManager);

                ProvisionManager provisionManager = BaseProvisionManager
                        .getInstance(getFilesDir());
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
                if (EXIT_CODE_SUCCESS == exitCode
                        || REQUEST_RESTART == exitCode || REQUEST_REBOOT == exitCode) {
                    errorDetected = false;
                }
            } catch (Throwable e) {
                logger.atError().setCause(e).log("Error while running Nucleus core main thread");
            } finally {
                if (kernel != null) {
                    kernel.shutdown();
                }
            }
            return exitCode;
        }
    }

    /**
     * Starting Nucleus as Android Foreground Service.
     *
     * @param context context of application
     */
    public static synchronized void launch(Context context) {
        initLogger(context);
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
                        DefaultGreengrassComponentService.class.getCanonicalName(),
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
                        DefaultGreengrassComponentService.class.getCanonicalName(),
                        logger);
            } catch (Throwable e) {
                logger.atError().setCause(e).log("Couldn't stop Nucleus service");
            }
        }
    }

    /**
     * Schedules restart of Nucleus.
     *
     * @param dueToError true when error occured
     */
    public void scheduleRestart(boolean dueToError) {
        if (!dueToError) {
            // Roll back start attempts counter for normal restarts as they are considered valid
            startAttemptsCounter--;
            logger.atDebug().log("Start attempts counter rolled back for normal restart");
        }

        if (startAttemptsCounter < NUCLEUS_START_ATTEMPTS_LIMIT) {
            Intent intent = new Intent();
            intent.setFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(ACTION_START_COMPONENT);
            intent.setComponent(
                    new ComponentName(this.getPackageName(), DefaultGreengrassComponentService.class.getCanonicalName())
            );
            intent.putExtra(EXTRA_START_ATTEMPTS_COUNTER, startAttemptsCounter);

            PendingIntent pendingIntent = PendingIntent.getService(this,
                    NUCLEUS_RESTART_INTENT_ID,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE | FLAG_ONE_SHOT);
            AlarmManager mgr = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
            mgr.set(AlarmManager.RTC, System.currentTimeMillis() + NUCLEUS_RESTART_DELAY_MS, pendingIntent);
        } else {
            logger.atError()
                    .log("Nucleus startup attempts limit reached. Service will not run. "
                            + "Check the integrity of your installation and configuration");
        }
    }

    @Override
    public void onCreate() {
        try {
            super.onCreate();
            packageManager = new AndroidBasePackageManager(this);
            initLogger(getContext());
        } catch (Throwable e) {
            if (logger != null) {
                logger.atError().setCause(e).log("Unexpected exception");
            }
        }
    }

    @Override
    public void onDestroy() {
        try {
            logger.atDebug().log("onDestroy");
            if (exitCode == REQUEST_RESTART) {
                scheduleRestart(false);
            } else if (errorDetected) {
                scheduleRestart(true);
            } else {
                // Nucleus terminated cleanly, no need to restart
            }
            super.onDestroy();
        } catch (Throwable e) {
            if (logger != null) {
                logger.atError().setCause(e).log("Unexpected exception");
            }
        }
    }

    @Override
    public Notification getNotification() {
        return NotManager.notForService(this, getString(R.string.not_title));
    }

    @Override
    public int getNotificationId() {
        return SERVICE_NOT_ID;
    }


    private static synchronized void initLogger(Context context) {
        if (logger == null) {
            logger = LogHelper.getLogger(context.getFilesDir(), DefaultGreengrassComponentService.class);
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

    // Implementation of AndroidServiceLevelAPI interface
    @Override
    public void terminate(int status) {
        exitCode = status;
        myThread.interrupt();
    }
}
