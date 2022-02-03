/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

#if ANDROID
package com.aws.greengrass.android.service;

import static com.aws.greengrass.android.managers.NotManager.SERVICE_NOT_ID;
import static java.lang.Thread.NORM_PRIORITY;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.aws.greengrass.android.managers.BaseComponentManager;
import com.aws.greengrass.android.managers.NotManager;
import com.aws.greengrass.android.utils.NucleusContentProvider;
import com.aws.greengrass.easysetup.GreengrassSetup;
import com.aws.greengrass.lifecyclemanager.AndroidExternalService;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.nucleus.R;

import java.io.File;

public class NucleusForegroundService extends Service {

    private static final String START_NUCLEUS = "com.aws.greengrass.START_NUCLEUS";
    public static final String STOP_NUCLEUS = "com.aws.greengrass.STOP_NUCLEUS";
    private static final String COMPONENT_STARTED = "com.aws.greengrass.COMPONENT_STARTED";
    private static final String COMPONENT_STOPPED = "com.aws.greengrass.COMPONENT_STOPPED";
    private static final String KEY_COMPONENT_PACKAGE = "KEY_PACKAGE";
    private Kernel kernel;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String componentPackage = intent.getStringExtra(KEY_COMPONENT_PACKAGE);
            if (action != null && action.equals(STOP_NUCLEUS)) {
                stopService();
            } else {
                if (action != null
                        && !TextUtils.isEmpty(componentPackage)) {
                    componentLifeCircle(action, componentPackage);
                }
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
            kernel = GreengrassSetup.main(fakeArgs);
            /* FIXME: Implement right way */
            while (true) {
                Thread.sleep(30 * 1000);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    });

    public static void launch() {
        new BaseComponentManager().startService(NucleusContentProvider.getApp().getPackageName(),
                NucleusForegroundService.class.getCanonicalName(),
                START_NUCLEUS);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        registerReceiver(receiver, getComponentFilter());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent.getAction() != null
                && intent.getAction().equals(START_NUCLEUS)) {
            Notification notification = NotManager
                    .notForService(this, getString(R.string.not_title));
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
        intentFilter.addAction(STOP_NUCLEUS);
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
                e.printStackTrace();
            }
        }
    }
}
#endif
