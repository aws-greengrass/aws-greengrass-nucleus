/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import android.app.AlarmManager;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
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
import com.aws.greengrass.android.component.core.GreengrassComponentService;
import com.aws.greengrass.android.managers.AndroidBasePackageManager;
import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.android.provision.BaseProvisionManager;
import com.aws.greengrass.android.provision.ProvisionManager;
import com.aws.greengrass.android.util.LogHelper;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.nucleus.R;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import java.util.List;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_ONE_SHOT;
import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_COMPONENT_STARTED;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_COMPONENT_STOPPED;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_START_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.ACTION_STOP_COMPONENT;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_FAILED;
import static com.aws.greengrass.android.component.utils.Constants.EXIT_CODE_SUCCESS;
import static com.aws.greengrass.android.component.utils.Constants.EXTRA_COMPONENT_PACKAGE;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.ipc.IPCEventStreamService.DEFAULT_PORT_NUMBER;

public class NucleusForegroundService extends GreengrassComponentService
        implements AndroidServiceLevelAPI, AndroidContextProvider {
    private static final Integer NUCLEUS_RESTART_DELAY_MS = 3000;
    private static final Integer NUCLEUS_RESTART_INTENT_ID = 0;
    private static final String EXTRA_START_ATTEMPTS_COUNTER = "START_ATTEMPTS_COUNTER";
    private static final int NUCLEUS_START_ATTEMPTS_LIMIT = 3;

    private Thread myThread;
    private Logger logger;
    private AndroidBasePackageManager packageManager;
    private int startAttemptsCounter = NUCLEUS_START_ATTEMPTS_LIMIT;

    // Service exit code.
    public int exitCode = EXIT_CODE_FAILED; // assume failure by default
    private boolean errorDetected = true; // assume failure by default

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
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_START_COMPONENT.equals(intent.getAction())
        && intent.hasExtra(EXTRA_START_ATTEMPTS_COUNTER)) {
            startAttemptsCounter = intent.getIntExtra(EXTRA_START_ATTEMPTS_COUNTER, 1);
            if (startAttemptsCounter < 1 || startAttemptsCounter > NUCLEUS_START_ATTEMPTS_LIMIT) {
                startAttemptsCounter = NUCLEUS_START_ATTEMPTS_LIMIT;
            }
        } else {
            // There's no intent or the intent is missing start attempts counter.
            startAttemptsCounter = 1;
        }
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public int doWork() {
        if (startAttemptsCounter > NUCLEUS_START_ATTEMPTS_LIMIT) {
            return EXIT_CODE_FAILED;
        } else {
            Kernel kernel = null;
            try {
                // save current thread object for future references
                myThread = Thread.currentThread();

                AndroidPlatform platform = (AndroidPlatform) Platform.getInstance();
                platform.setAndroidServiceLevelAPIs(this, packageManager);
                platform.setAndroidContextProvider(this);

                ProvisionManager provisionManager = BaseProvisionManager.getInstance(getFilesDir());
                final String[] nucleusArguments = provisionManager.prepareArguments();
                kernel = GreengrassSetup.main(nucleusArguments);

                // Clear system properties
                provisionManager.clearSystemProperties();

                // waiting for Thread.interrupt() call
                while (true) {
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                logger.atInfo().log("Nucleus thread terminated, exitCode ", exitCode);
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
     * @param context Context of android application.
     * @throws RuntimeException on errors
     */
    public static void launch(@NonNull Context context) throws RuntimeException {
        startService(context,
                context.getPackageName(),
                NucleusForegroundService.class.getCanonicalName(),
                ACTION_START_COMPONENT);
    }

    /**
     * Stop Nucleus as Android Foreground Service.
     *
     * @param context Context of android application.
     * @throws RuntimeException on errors
     */
    public static void finish(@NonNull Context context) throws RuntimeException {
        stopService(context,
                context.getPackageName(),
                NucleusForegroundService.class.getCanonicalName(),
                ACTION_STOP_COMPONENT);
    }

    public void scheduleRestart(boolean dueToError) {
        if (dueToError) {
            startAttemptsCounter++;
        }

        if (startAttemptsCounter <= NUCLEUS_START_ATTEMPTS_LIMIT) {
            Intent intent = new Intent();
            intent.setFlags(FLAG_ACTIVITY_CLEAR_TASK | FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(ACTION_START_COMPONENT);
            intent.setComponent(
                    new ComponentName(this.getPackageName(), NucleusForegroundService.class.getCanonicalName())
            );
            intent.putExtra(EXTRA_START_ATTEMPTS_COUNTER, startAttemptsCounter);

            PendingIntent pendingIntent = PendingIntent.getService(this,
                    NUCLEUS_RESTART_INTENT_ID,
                    intent,
                    PendingIntent.FLAG_CANCEL_CURRENT | FLAG_IMMUTABLE | FLAG_ONE_SHOT);
            AlarmManager mgr = (AlarmManager) this.getSystemService(Context.ALARM_SERVICE);
            mgr.set(AlarmManager.RTC, System.currentTimeMillis() + NUCLEUS_RESTART_DELAY_MS, pendingIntent);
        } else {
            logger.atError().log("Nucleus startup attempts limit reached. Service will not run." +
                    " Check the integrity of your installation and configuration");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        logger = LogHelper.getLogger(getFilesDir(), getClass());
        packageManager = new AndroidBasePackageManager(this);

        // FIXME: remove that code when provide field in config file
        System.setProperty("ipc.socket.port", String.valueOf(DEFAULT_PORT_NUMBER));
        registerReceiver(receiver, getIntentFilter());
    }

    @Override
    public void onDestroy() {
        logger.atDebug().log("onDestroy");
        unregisterReceiver(receiver);
        if (exitCode == REQUEST_RESTART) {
            scheduleRestart(false);
        } else if (errorDetected) {
            scheduleRestart(true);
        } else {
            // Nucleus terminated cleanly, no need to restart
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

    private IntentFilter getIntentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_COMPONENT_STARTED);
        intentFilter.addAction(ACTION_COMPONENT_STOPPED);
        return intentFilter;
    }

    private void handleComponentResponses(String action, String sourcePackage)
            throws ServiceLoadException {
        logger.atDebug().log("Handling component response action {} sourcePackage {}", action, sourcePackage);
        /* Rework that code
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
                    default:
                        ;
                }
            }
        }
        */
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
            intent.setComponent(null);
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
        stopService(getApplication(), packageName, className, action);
    }

    /**
     * Initiate stopping Android component was started as Foreground Service.
     *
     * @param context     Context of Activity or Foreground service
     * @param packageName Android Package to start.
     * @param className   Class name of the ForegroundService.
     * @param action      Action of Intent to send.
     * @throws RuntimeException on errors
     */
    public static void stopService(@NonNull Context context, @NonNull String packageName, @NonNull String className,
                                   @NonNull String action) throws RuntimeException {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, className));
        intent.setAction(action);
        intent.setPackage(packageName);
        List<ResolveInfo> matches = context.getPackageManager().queryIntentServices(intent, 0);
        if (matches.size() == 1) {
            intent.setComponent(null);
            context.sendBroadcast(intent);
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
        myThread.interrupt();
    }
}
