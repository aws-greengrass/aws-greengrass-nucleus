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
import com.aws.greengrass.android.component.core.ComponentWorkerThread;
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
import software.amazon.awssdk.aws.greengrass.model.InvalidArgumentsError;

import java.util.HashMap;
import java.util.concurrent.ExecutionException;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_RESTART_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_TERMINATED;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;

public class DefaultGreengrassComponentService extends GreengrassComponentService
        implements AndroidServiceLevelAPI, AndroidContextProvider {
    private static final Integer NUCLEUS_RESTART_DELAY_MS = 3000;
    private static final Integer NUCLEUS_RESTART_INTENT_ID = 0;
    private static final int NUCLEUS_START_ATTEMPTS_LIMIT = 3;

    /** Nucleus service initialization thread. */
    private Thread myThread = null;
    /** Counter for Nucleus startup attempts. */
    private static Integer startAttemptsCounter = new Integer(0);
    /** Indicator that Nucleus execution resulted in an error. */
    private boolean errorDetected = true; // assume failure by default

    // initialized in onCreate()
    private AndroidBasePackageManager packageManager;

    // initialized by launch()
    private static Logger logger;
    private static AndroidComponentManager componentManager;
    private static final String authToken = Utils.generateRandomString(16).toUpperCase();

    private NucleusWorkerThread componentWorkerThread = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (startAttemptsCounter) {
            try {
                if (intent == null || ACTION_RESTART_COMPONENT.equals(intent.getAction())) {
                    if (startAttemptsCounter < NUCLEUS_START_ATTEMPTS_LIMIT) {
                        launch(this);
                        return START_STICKY;
                    } else {
                        throw new RuntimeException("Startup attempts limit reached");
                    }
                } else if (ACTION_START_COMPONENT.equals(intent.getAction())) {
                    // Do normal startup if everything is fine
                    startAttemptsCounter++;
                    super.onStartCommand(intent, flags, startId);
                    return START_STICKY;
                } else {
                    // Unknown action - ignore the intent
                    throw new InvalidArgumentsError("Unsupported action in start intent: "
                            + intent.getAction());
                }
            } catch (Throwable e) {
                if (logger != null) {
                    logger.atError().setCause(e).log("Fatal error at startup");
                }

                // Abort startup by reporting not-sticky start
                stopSelf();
                return START_NOT_STICKY;
            }
        }
    }

    private class NucleusWorkerThread extends ComponentWorkerThread {

        @Override
        public void run() {
            // This is the protection from malformed counter
            synchronized (startAttemptsCounter) {
                if (startAttemptsCounter > NUCLEUS_START_ATTEMPTS_LIMIT) {
                    logger.atError()
                            .log("Startup attempts counter is over the limit. "
                                    + "Probably, startup intent is malformed. Startup aborted");
                    workerExitCode = EXIT_CODE_FAILED;
                    return;
                }
            }

            Kernel kernel = null;
            try {
                // Enter critical section to avoid sudden interruption of Nucleus startup process
                enterCriticalSection();
                logger.atDebug().log("Entered critical section for Nucleus startup");

                AndroidPlatform platform = (AndroidPlatform) Platform.getInstance();
                platform.setAndroidAPIs(DefaultGreengrassComponentService.this, packageManager, componentManager);

                ProvisionManager provisionManager = BaseProvisionManager.getInstance(getFilesDir());
                final String[] nucleusArguments = provisionManager.prepareArguments();
                kernel = GreengrassSetup.main(nucleusArguments);

                // Clear system properties
                provisionManager.clearSystemProperties();

                if (!provisionManager.isProvisioned()) {
                    provisionManager.writeConfig(kernel);
                }

                // Startup is done, now we can leave critical section and allow to terminate Nucleus
                leaveCriticalSection();
                logger.atDebug().log("Nucleus startup is done, left critical section");

                // waiting for Thread.interrupt() call
                join();
            } catch (InterruptedException e) {
                logger.atInfo().kv("workerExitCode", workerExitCode)
                        .log("Nucleus worker thread interrupted");
            }  catch (Throwable e) {
                logger.atError().setCause(e)
                        .log("Error while running Nucleus core worker thread");
            } finally {
                // Ensure we are out of critical section before exiting
                leaveCriticalSection();
                if (kernel != null) {
                    kernel.shutdown();
                }
            }
        }
    }

    @Override
    protected void onExitRequested() {
        // Do not stop service immediately, just ask worker thread to exit
        // After that the service will be stopped by kernel.shutdown() call
        componentWorkerThread.stopWorker();
    }

    @Override
    protected void onServiceStart() {
        myThread = Thread.currentThread();
        componentWorkerThread = new NucleusWorkerThread();
        setComponentWorkerThread(componentWorkerThread);
    }

    @Override
    protected void onServiceStop() {
        if (EXIT_CODE_SUCCESS == exitCode || EXIT_CODE_TERMINATED == exitCode
                || REQUEST_RESTART == exitCode || REQUEST_REBOOT == exitCode) {
            errorDetected = false;
        }
    }

    /**
     * Resets start attempts counter back to zero.
     */
    public static void resetStartAttemptsCounter() {
        synchronized (startAttemptsCounter) {
            startAttemptsCounter = 0;
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
        synchronized (startAttemptsCounter) {
            if (!dueToError) {
                // Roll back start attempts counter for normal restarts as they are considered valid
                startAttemptsCounter--;
                logger.atDebug().log("Start attempts counter rolled back for normal restart");
            }

            if (startAttemptsCounter < NUCLEUS_START_ATTEMPTS_LIMIT) {
                Intent intent = new Intent();
                intent.setFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
                intent.setAction(ACTION_RESTART_COMPONENT);
                intent.setComponent(
                        new ComponentName(this.getPackageName(),
                                DefaultGreengrassComponentService.class.getCanonicalName())
                );

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
        if (myThread != null && myThread.isAlive()) {
            myThread.interrupt();
        }
    }
}
