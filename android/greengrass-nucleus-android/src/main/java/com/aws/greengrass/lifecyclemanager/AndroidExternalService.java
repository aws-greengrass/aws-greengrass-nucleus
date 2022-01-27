/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.aws.greengrass.android.ContextHolder;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.Platform;

import java.io.File;

public class AndroidExternalService extends GenericExternalService {
    public static final String FOREGROUND_SERVICE = "ForegroundService";
    public static final String APK_INSTALL = "APKInstall";
    public static final String FILE = "File";
    public static final String FORCE = "Force";
    public static final String PACKAGE_ARCHIVE = "application/vnd.android.package-archive";
    public static final String ACTIVITY = "Activity";
    public static final String CLASS = "Class";
    public static final String STOP_SERVICE_COMPONENT = "com.aws.greengrass.STOP_SERVICE_COMPONENT";
    public static final String STOP_ACTIVITY_COMPONENT = "com.aws.greengrass.STOP_ACTIVITY_COMPONENT";
    public static final String START_ACTIVITY_COMPONENT = "com.aws.greengrass.START_ACTIVITY_COMPONENT";
    public static final String START_SERVICE_COMPONENT = "com.aws.greengrass.START_SERVICE_COMPONENT";

    /**
     * Create a new GenericExternalService.
     *
     * @param c root topic for this service.
     */
    public AndroidExternalService(Topics c) {
        super(c, Platform.getInstance());
    }

    /**
     * Create a new GenericExternalService.
     *
     * @param c        root topic for this service.
     * @param platform the platform instance to use.
     */
    public AndroidExternalService(Topics c, Platform platform) {
        super(c, c.lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC), platform);
    }

    protected AndroidExternalService(Topics c, Topics privateSpace) {
        super(c, privateSpace, Platform.getInstance());
    }

    @SuppressWarnings("PMD.UselessParentheses")
    protected AndroidExternalService(Topics c, Topics privateSpace, Platform platform) {
        super(c, privateSpace, platform);
    }

    @Override
    protected synchronized void install() throws InterruptedException {
        stopAllLifecycleProcesses();

        // reset runWith in case we moved from NEW -> INSTALLED -> change runwith -> NEW
        resetRunWith();

        if (installWork() == RunStatus.Errored) {
            serviceErrored("Script errored in install");
        }
    }

    // Synchronize startup() and shutdown() as both are non-blocking, but need to have coordination
    // to operate properly
    @Override
    protected synchronized void startup() throws InterruptedException {
        long startingStateGeneration = getStateGeneration();

        RunStatus result = foregroundServiceWork(Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC, START_SERVICE_COMPONENT);

        if (result == RunStatus.Errored) {
            serviceErrored("Script errored in startup");
        } else if (result == RunStatus.NothingDone && startingStateGeneration == getStateGeneration()
                && State.STARTING.equals(getState())) {
            handleRunScript();
        } else if (result == RunStatus.OK) {
            reportState(State.RUNNING);
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized void handleRunScript() {
        stopAllLifecycleProcesses();

        RunStatus result = activityWork(LIFECYCLE_RUN_NAMESPACE_TOPIC, START_ACTIVITY_COMPONENT);

        if (result == RunStatus.NothingDone) {
            reportState(State.FINISHED);
            logger.atInfo().setEventType("generic-service-finished").log("Nothing done");
        } else if (result == RunStatus.Errored) {
            serviceErrored("Script errored in run");
        } else if (result != RunStatus.OK) {
            reportState(State.RUNNING);
        }
    }

    @Override
    protected synchronized void shutdown() {
        logger.atInfo().log("Shutdown initiated");

        if (isPaused()) {
            // Resume if paused for a graceful shutdown
            try {
                resume(false, false);
            } catch (ServiceException e) {
                // Reset tracking flag
                paused.set(false);
                logger.atError().setCause(e).log("Could not resume service before shutdown, process will be killed");
            }
        }

        shutdownWork();

        stopAllLifecycleProcesses();
        // Clean up any resource manager entities (can be OS specific) that might have been created for this
        // component.
        systemResourceController.removeResourceController(this);
        logger.atInfo().setEventType("generic-service-shutdown").log();
        resetRunWith(); // reset runWith - a deployment can change user info
    }

    private void shutdownWork() {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC);
        if (n instanceof Topics) {
            Node serviceNode = ((Topics) n).getChild(FOREGROUND_SERVICE);
            Node activityNode = ((Topics) n).getChild(ACTIVITY);
            if (serviceNode instanceof Topics) {
                foregroundServiceWork(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, STOP_SERVICE_COMPONENT);
            } else if (activityNode instanceof Topics) {
                activityWork(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, STOP_ACTIVITY_COMPONENT);
            }
        }
    }

    private RunStatus foregroundServiceWork(String name, String action) {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(name);
        if (n instanceof Topics) {
            Node serviceNode = ((Topics) n).getChild(FOREGROUND_SERVICE);
            if (serviceNode instanceof Topics) {
                String className = Coerce.toString(((Topics) serviceNode).getChild(CLASS));
                String appPackage = Coerce.toString(getComponentName());
                if (className != null && appPackage != null) {
                    Intent intent = new Intent();
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    // FIXME: temp code, for testing
                    intent.putExtra("keyDescription", "Test component [1]");
                    intent.putExtra("keyTitle", "It was run from nucleus [2]");
                    intent.setAction(action);
                    intent.setComponent(new ComponentName(appPackage, className));
                    ResolveInfo resolveInfo = ContextHolder.getInstance().context.getPackageManager().resolveService(intent, 0);
                    if (resolveInfo != null) {
                        try {
                            ContextCompat.startForegroundService(ContextHolder.getInstance().context, intent);
                            return RunStatus.OK;
                        } catch (Exception e) {
                            return RunStatus.Errored;
                        }
                    } else {
                        return RunStatus.Errored;
                    }
                } else {
                    return RunStatus.Errored;
                }
            } else {
                return RunStatus.Errored;
            }
        }
        return RunStatus.NothingDone;
    }

    private RunStatus activityWork(String name, String action) {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(name);
        if (n instanceof Topics) {
            Node apkInstallNode = ((Topics) n).getChild(ACTIVITY);
            if (apkInstallNode instanceof Topics) {
                String className = Coerce.toString(((Topics) apkInstallNode).getChild(CLASS));
                String appPackage = Coerce.toString(getComponentName());
                if (className != null
                        && appPackage != null) {
                    Intent intent = new Intent();
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(appPackage, className));
                    intent.setAction(action);
                    if (intent.resolveActivityInfo(ContextHolder.getInstance().context.getPackageManager(), 0) != null) {
                        try {
                            ContextHolder.getInstance().context.startActivity(intent);
                            return RunStatus.OK;
                        } catch (Exception e) {
                            return RunStatus.Errored;
                        }
                    } else {
                        return RunStatus.Errored;
                    }
                } else {
                    return RunStatus.Errored;
                }
            } else {
                return RunStatus.Errored;
            }
        }
        return RunStatus.NothingDone;
    }

    private RunStatus installWork() throws InterruptedException {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC);
        if (n instanceof Topics) {
            Node apkInstallNode = ((Topics) n).getChild(APK_INSTALL);
            if (apkInstallNode instanceof Topics) {
                String file = Coerce.toString(((Topics) apkInstallNode).getChild(FILE));
                String appPackage = Coerce.toString(getComponentName());
                //FIXME: find solution for version form recipe header
                String version = "";//Coerce.toString(getAndroidPackageVersionTopic());
                boolean force = Coerce.toBoolean(((Topics) apkInstallNode).getChild(FORCE));
                if (file != null
                        && appPackage != null
                        && version != null) {
                    PackageInfo packageInfo = getPackageInfo(appPackage);
                    if (packageInfo != null
                            && (!version.equals(packageInfo.versionName) || force)) {
                        File apkFile = new File(file);
                        if (apkFile.exists()) {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                Uri downloaded_apk = FileProvider.getUriForFile(
                                        ContextHolder.getInstance().context,
                                        ContextHolder.getInstance().context.getApplicationContext().getPackageName() + ".provider",
                                        apkFile);
                                intent.setDataAndType(downloaded_apk, PACKAGE_ARCHIVE);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                ContextHolder.getInstance().context.startActivity(intent);
                            } catch (Exception e) {
                                return RunStatus.Errored;
                            }
                            // FIXME: come up with a more elegant way
                            while (true) {
                                PackageManager pm = ContextHolder.getInstance().context.getPackageManager();
                                try {
                                    Thread.sleep(5000);
                                    pm.getPackageInfo(appPackage, 0);
                                    break;
                                } catch (PackageManager.NameNotFoundException ignored) {
                                }
                            }
                            return RunStatus.OK;
                        }
                    }
                } else {
                    return RunStatus.Errored;
                }
            }
        }
        return RunStatus.NothingDone;
    }

    private PackageInfo getPackageInfo(String appPackage) {
        PackageManager pm = ContextHolder.getInstance().context.getPackageManager();
        try {
            return pm.getPackageInfo(appPackage, 0);
        } catch (PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    private String getComponentName() {
        return config.getName();
    }
}
