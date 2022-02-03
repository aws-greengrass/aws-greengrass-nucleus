/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.TIMEOUT_NAMESPACE_TOPIC;

import com.aws.greengrass.android.managers.AndroidComponentManager;
import com.aws.greengrass.android.managers.BaseComponentManager;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.Platform;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AndroidExternalService extends GenericExternalService {
    public static final String FOREGROUND_SERVICE = "ForegroundService";
    public static final String APK_INSTALL = "APKInstall";
    public static final String FILE = "File";
    public static final String ACTIVITY = "Activity";
    public static final String CLASS = "Class";
    public static final String START_COMPONENT = "com.aws.greengrass.START_COMPONENT";
    public static final String STOP_COMPONENT = "com.aws.greengrass.STOP_COMPONENT";
    private final Integer shutdown = 0;

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
                    status = RunStatus.Errored;
                    while (getState() == State.NEW) {
                        if (componentManager.isPackageInstalled(packageName, time)) {
                            status = RunStatus.OK;
                            break;
                        }
                    }
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

        RunStatus result = foregroundServiceWork(LIFECYCLE_STARTUP_NAMESPACE_TOPIC, START_COMPONENT);

        if (result == RunStatus.Errored) {
            serviceErrored("Script errored in startup");
        } else if (result == RunStatus.NothingDone && startingStateGeneration == getStateGeneration()
                && State.STARTING.equals(getState())) {
            handleRunScript();
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
            Topic timeoutTopic = config.find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC,
                    TIMEOUT_NAMESPACE_TOPIC);
            Integer timeout = timeoutTopic == null ? null : (Integer) timeoutTopic.getOnce();
            if (timeout != null) {
                context.get(ScheduledExecutorService.class).schedule(() -> {
                    if (getState() == State.STARTING) {
                        logger.atWarn("service-run-timed-out")
                                .log("Service failed to run within timeout, calling close in process");
                        reportState(State.ERRORED);
                    }
                }, timeout, TimeUnit.SECONDS);
            }
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
        try {
            shutdown.wait();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void shutdownWork() {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC);
        if (n instanceof Topics) {
            Node serviceNode = ((Topics) n).getChild(FOREGROUND_SERVICE);
            Node activityNode = ((Topics) n).getChild(ACTIVITY);
            if (serviceNode instanceof Topics) {
                foregroundServiceWork(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, STOP_COMPONENT);
            } else if (activityNode instanceof Topics) {
                activityWork(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, STOP_COMPONENT);
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
                boolean completed = false;
                switch (action) {
                    case START_COMPONENT:
                        completed = componentManager.startService(packageName, className, action);
                        break;
                    case STOP_COMPONENT:
                        completed = componentManager.stopService(packageName, className, action);
                        break;
                }
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
                boolean completed = false;
                switch (action) {
                    case START_COMPONENT:
                        completed = componentManager.startActivity(packageName, className, action);
                        break;
                    case STOP_COMPONENT:
                        completed = componentManager.stopActivity(packageName, className, action);
                        break;
                }
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

    public void componentRunning() {
        reportState(State.RUNNING);
    }

    public void componentFinished() {
        // the component terminated itself
        if (getState() == State.RUNNING) {

        } else {
            shutdown.notifyAll();
        }
    }
}

