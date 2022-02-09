/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.android.service;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_START_ACTION;
import static com.aws.greengrass.lifecyclemanager.AndroidExternalService.DEFAULT_STOP_ACTION;
import static java.lang.Thread.NORM_PRIORITY;

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

import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.lifecyclemanager.AndroidExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.nucleus.R;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.android.AndroidAppLevelAPI;
import com.aws.greengrass.util.platforms.android.AndroidPlatform;
import com.aws.greengrass.util.platforms.android.AndroidServiceLevelAPI;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class NucleusForegroundService extends Service implements AndroidServiceLevelAPI {
    private static final String COMPONENT_STARTED = "com.aws.greengrass.COMPONENT_STARTED";
    private static final String COMPONENT_STOPPED = "com.aws.greengrass.COMPONENT_STOPPED";
    private static final String KEY_COMPONENT_PACKAGE = "KEY_PACKAGE";

    // FIXME: probably arch mistake; about direct usage of Kernel
    private Kernel kernel;
    // TODO: remove this reference when got rid of onNewIntent()
    private static AndroidAppLevelAPI androidAppLevelAPI;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            // TODO: read also completion code when STOPPED
            String componentPackage = intent.getStringExtra(KEY_COMPONENT_PACKAGE);
            // FIXME: check also componentPackage to avoid stopping Nucleus on each stop command ?
            if (DEFAULT_STOP_ACTION.equals(action)) {
                stopService();
            } else if (action != null && !TextUtils.isEmpty(componentPackage)) {
                componentLifeCircle(action, componentPackage);
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
            final String[] fakeArgs = {"--setup-system-service", "false"};

            // FIXME: remove first call when got rid of onNewIntent
            ((AndroidPlatform)Platform.getInstance()).setAndroidAppLevelAPI(androidAppLevelAPI);
            ((AndroidPlatform)Platform.getInstance()).setAndroidServiceLevelAPI(this);
            kernel = GreengrassSetup.main(fakeArgs);
            /* FIXME: Implement by right way */
            while (true) {
                Thread.sleep(30 * 1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    });

    /**
     *  Starting Nucleus as Android Foreground Service.
     *
     * @param context Context of android application.
     * @param androidAppLvlAPI Application Level API.
     */
    public static void launch(Context context, AndroidAppLevelAPI androidAppLvlAPI) {
        androidAppLevelAPI = androidAppLvlAPI;
        startService(context, context.getPackageName(),
                NucleusForegroundService.class.getCanonicalName(),
                DEFAULT_START_ACTION);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(receiver, getComponentFilter());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (DEFAULT_START_ACTION.equals(intent.getAction())) {
            Notification notification = NotManager.notForService(this, getString(R.string.not_title));
            startForeground(SERVICE_NOT_ID, notification);
            if (!nucleusThread.isAlive()) {
                nucleusThread.setPriority(NORM_PRIORITY);
                nucleusThread.start();
            }
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
        unregisterReceiver(receiver);
        super.onDestroy();
    }

    private void stopService() {
        stopForeground(true);
        stopSelf();
    }

    private IntentFilter getComponentFilter() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(DEFAULT_STOP_ACTION);
        intentFilter.addAction(COMPONENT_STARTED);
        intentFilter.addAction(COMPONENT_STOPPED);
        return intentFilter;
    }

    private void componentLifeCircle(String action, String componentPackage) {
        if (kernel != null) {
            GreengrassService component;
            try {
                component = kernel.locate(componentPackage);
                if (component instanceof AndroidExternalService) {
                    AndroidExternalService androidComponent = (AndroidExternalService) component;
                    switch (action) {
                        case COMPONENT_STARTED:
                            androidComponent.componentRunning();
                            break;
                        case COMPONENT_STOPPED:
                            androidComponent.componentFinished();
                            break;
                    }
                }
            } catch (ServiceLoadException e) {
                // FIXME: send to nucleus log
                e.printStackTrace();
            }
        }
    }


    // Implementation methods of AndroidComponentManager
    @Override
    public void startActivity(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws RuntimeException {
        Application app = getApplication();
        Intent intent = new Intent();
        ComponentName componentName = new ComponentName(packageName, className);
        intent.setComponent(componentName);
        intent.setAction(action);
        if (intent.resolveActivityInfo(app.getPackageManager(), 0) != null) {
            NotManager.notForActivityComponent(app, intent
                    , app.getString(com.aws.greengrass.nucleus.R.string.click_to_start_component));
        } else {
            throw new RuntimeException("Could not find Activity " + componentName);
        }
    }

    @Override
    public void stopActivity(String packageName, @NonNull String className, @NonNull String action)
            throws RuntimeException {
        Application app = getApplication();
        Intent intent = new Intent();
        ComponentName componentName = new ComponentName(packageName, className);
        intent.setComponent(componentName);
        intent.setAction(action);
        intent.setPackage(packageName);
        if (intent.resolveActivityInfo(app.getPackageManager(), 0) != null) {
            app.sendBroadcast(intent);
        } else {
            throw new RuntimeException("Could not find Activity " + componentName);
        }
    }

    @Override
    public void startService(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws RuntimeException {
        startService(getApplication(), packageName, className, action);
    }

    @Override
    public void stopService(@NonNull String packageName, @NonNull String className
            , @NonNull String action) throws RuntimeException {
        Application app = getApplication();
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, className));
        intent.setAction(action);
        intent.setPackage(packageName);
        List<ResolveInfo> matches = app.getPackageManager().queryIntentServices(intent, 0);
        if (matches.size() == 1) {
            app.sendBroadcast(intent);
        } else {
            throw new RuntimeException("Ambiguity in service");
        }
    }

    // Implementation of methods from AndroidUserId interface
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

    /**
     * Initiate starting Android component as Foreground Service.
     *
     * @param context Context of Activity or Foreground service
     * @param packageName Android Package to start.
     * @param className Class name of the ForegroundService.
     * @param action Action of Intent to send
     * @throws IOException on errors
     */
    public static void startService(@NonNull Context context, @NonNull String packageName, @NonNull String className
            , @NonNull String action) throws RuntimeException {
        Intent intent = new Intent();
        intent.setFlags(FLAG_ACTIVITY_NEW_TASK);
        intent.setAction(action);
        intent.setComponent(new ComponentName(packageName, className));
        List<ResolveInfo> matches = context.getPackageManager().queryIntentServices(intent, 0);
        if (matches.size() == 1) {
            ContextCompat.startForegroundService(context, intent);
        } else {
            throw new RuntimeException("Ambiguity in service");
        }
    }
}
