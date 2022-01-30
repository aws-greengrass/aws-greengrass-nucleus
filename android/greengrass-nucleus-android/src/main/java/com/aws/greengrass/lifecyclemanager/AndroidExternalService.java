/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.Platform;

public class AndroidExternalService extends GenericExternalService {
    public static final String FOREGROUND_SERVICE = "ForegroundService";
    public static final String APK_INSTALL = "APKInstall";
    public static final String FILE = "File";
    public static final String ACTIVITY = "Activity";
    public static final String CLASS = "Class";
    public static final String START_COMPONENT = "com.aws.greengrass.START_COMPONENT";
    public static final String STOP_COMPONENT = "com.aws.greengrass.STOP_COMPONENT";

    private final AndroidComponentManager componentManager = new BaseComponentManager();

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

        RunStatus status = RunStatus.NothingDone;
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(LIFECYCLE_INSTALL_NAMESPACE_TOPIC);
        if (n instanceof Topics) {
            Node apkInstallNode = ((Topics) n).getChild(APK_INSTALL);
            if (apkInstallNode instanceof Topics) {
                String file = Coerce.toString(((Topics) apkInstallNode).getChild(FILE));
                String packageName = Coerce.toString(getComponentName());
                long time = componentManager.getPackageLastUpdateTime(packageName);
                boolean startedInstall = componentManager.installPackage(file, packageName);
                if (startedInstall) {
                    while (true) {
                        if (componentManager.isPackageInstalled(packageName,time )) {
                            break;
                        }
                    }
                    status = RunStatus.OK;
                } else {
                    status = RunStatus.Errored;
                }
            }
        }

        if (status == RunStatus.Errored) {
            serviceErrored("Script errored in install");
        }
    }

    // Synchronize startup() and shutdown() as both are non-blocking, but need to have coordination
    // to operate properly
    @Override
    protected synchronized void startup() throws InterruptedException {
        long startingStateGeneration = getStateGeneration();

        RunStatus result = foregroundServiceWork(Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC, START_COMPONENT);

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

        RunStatus result = activityWork(LIFECYCLE_RUN_NAMESPACE_TOPIC, START_COMPONENT);

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
                foregroundServiceWork(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, STOP_COMPONENT);
            } else if (activityNode instanceof Topics) {
                activityWork(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, STOP_COMPONENT);
            }
        }
    }

    private RunStatus foregroundServiceWork(String name, String action) {
        RunStatus status = RunStatus.NothingDone;
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(name);
        if (n instanceof Topics) {
            Node serviceNode = ((Topics) n).getChild(FOREGROUND_SERVICE);
            if (serviceNode instanceof Topics) {
                String packageName = Coerce.toString(getComponentName());
                String className = Coerce.toString(((Topics) serviceNode).getChild(CLASS));
                boolean completed = componentManager.sendServiceAction(packageName, className, action);
                if (completed) {
                    status = RunStatus.OK;
                } else {
                    status = RunStatus.Errored;
                }
            } else {
                status = RunStatus.Errored;
            }
        }
        return status;
    }

    private RunStatus activityWork(String name, String action) {
        RunStatus status = RunStatus.NothingDone;
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(name);
        if (n instanceof Topics) {
            Node apkInstallNode = ((Topics) n).getChild(ACTIVITY);
            if (apkInstallNode instanceof Topics) {
                String packageName = Coerce.toString(getComponentName());
                String className = Coerce.toString(((Topics) apkInstallNode).getChild(CLASS));
                boolean completed = componentManager.sendActivityAction(packageName, className, action);
                if (completed) {
                    status = RunStatus.OK;
                } else {
                    status = RunStatus.Errored;
                }
            } else {
                return RunStatus.Errored;
            }
        }
        return status;
    }

    private String getComponentName() {
        return config.getName();
    }
}
