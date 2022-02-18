/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.Platform;
import lombok.AllArgsConstructor;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.TIMEOUT_NAMESPACE_TOPIC;

public class AndroidExternalService extends GenericExternalService {
    // items of recipe
    private static final String FOREGROUND_SERVICE = "ForegroundService";
    private static final String APK_INSTALL_TOPIC = "APKInstall";
    private static final String FILE_TOPIC = "File";
    private static final String FORCE_TOPIC = "Force";
    private static final String ACTIVITY_TOPIC = "Activity";
    private static final String CLASS_TOPIC = "Class";
    private static final String INTENT_TOPIC = "Intent";

    /** Default class name of Activity.
     */
    public static final String DEFAULT_ACTIVITY_CLASSNAME = ".DefaultGreengrassComponentActivity";

    /** Default class name of ForegroundService.
     */
    public static final String DEFAULT_SERVICE_CLASSNAME = ".DefaultGreengrassComponentService";

    /** Default start Intent action.
     */
    public static final String DEFAULT_START_ACTION = "com.aws.greengrass.START_COMPONENT";

    /** Default stop Intent action.
     */
    public static final String DEFAULT_STOP_ACTION = "com.aws.greengrass.STOP_COMPONENT";

    private boolean isComponentShutdown = false;

    /**
     * Create a new AndroidExternalService.
     *
     * @param c root topic for this service.
     */
    public AndroidExternalService(Topics c) {
        super(c, Platform.getInstance());
    }

    /**
     * Create a new AndroidExternalService.
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

        // FIXME: move installApk() to bootstrap
        RunStatus status = installApk(LIFECYCLE_INSTALL_NAMESPACE_TOPIC);
        if (status == RunStatus.Errored) {
            serviceErrored("Script errored in install");
        }
    }

    // Synchronize startup() and shutdown() as both are non-blocking, but need to have coordination
    // to operate properly
    @Override
    protected synchronized void startup() throws InterruptedException {
        long startingStateGeneration = getStateGeneration();

        RunStatus result = foregroundServiceWork(LIFECYCLE_STARTUP_NAMESPACE_TOPIC);

        if (result == RunStatus.Errored) {
            serviceErrored("Android component errored in startup");
        } else if (result == RunStatus.NothingDone && startingStateGeneration == getStateGeneration()
                && State.STARTING.equals(getState())) {
            handleActivity();
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized void handleActivity() {
        stopAllLifecycleProcesses();

        RunStatus result = activityWork(LIFECYCLE_RUN_NAMESPACE_TOPIC);

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

        isComponentShutdown = false;
        shutdownWork();

        stopAllLifecycleProcesses();
        // Clean up any resource manager entities (can be OS specific) that might have been created for this
        // component.
        systemResourceController.removeResourceController(this);
        logger.atInfo().setEventType("generic-service-shutdown").log();
        resetRunWith(); // reset runWith - a deployment can change user info
        // FIXME: remove busy loop
        while (getState() == State.STOPPING) {
            if (isComponentShutdown) {
                break;
            }
        }
    }

    private void shutdownWork() {
        Node n = (getLifecycleTopic() == null) ? null : 
                    getLifecycleTopic().getChild(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC);
        if (n instanceof Topics) {
            Node serviceNode = ((Topics) n).getChild(FOREGROUND_SERVICE);
            Node activityNode = ((Topics) n).getChild(ACTIVITY_TOPIC);
            if (serviceNode instanceof Topics) {
                foregroundServiceWork(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC);
            } else if (activityNode instanceof Topics) {
                activityWork(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC);
            }
        }
    }

    @AllArgsConstructor
    private class ReadParametersResult {
        private RunStatus status;
        private String packageName;
        private String className;
        private String action;

        ReadParametersResult(RunStatus status) {
            this.status = status;
        }
    }

    private ReadParametersResult readParameters(String topicName, String subTopicName) {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(topicName);
        if (!(n instanceof Topics)) {
            // "startup" or "shutdown" does not exist
            logger.atDebug().kv("lifecycle", topicName).log("{} is not required: service {} lifecycle not found", 
                    topicName, topicName);
            return new ReadParametersResult(RunStatus.NothingDone);
        }

        Node node = ((Topics) n).getChild(subTopicName);
        if (!(node instanceof Topics)) {
            // "ForegroundService" does not exists
            logger.atError().kv("lifecycle", topicName).log("ForegroundService not found");
            return new ReadParametersResult(RunStatus.Errored);
        }

        String packageName = Coerce.toString(getComponentName());

        String className = Coerce.toString(((Topics) node).getChild(CLASS_TOPIC));
        if (className == null || className.isEmpty()) {
            className = DEFAULT_SERVICE_CLASSNAME;
        }

        // prepend ".className" string with package name
        if (className.startsWith(".")) {
            className = packageName + className;
        }

        String action = Coerce.toString(((Topics) node).getChild(INTENT_TOPIC));
        if (action == null || action.isEmpty()) {
            if (topicName.equals(LIFECYCLE_STARTUP_NAMESPACE_TOPIC)
                    || topicName.equals(LIFECYCLE_RUN_NAMESPACE_TOPIC)) {
                action = DEFAULT_START_ACTION;
            } else {
                action = DEFAULT_STOP_ACTION;
            }
        } else {
            if ((topicName.equals(LIFECYCLE_STARTUP_NAMESPACE_TOPIC)
                    || topicName.equals(LIFECYCLE_RUN_NAMESPACE_TOPIC))
                    && action.equals(DEFAULT_STOP_ACTION)) {
                logger.atError().kv("lifecycle", topicName).log("Stop intent action is used to startup service");
                return new ReadParametersResult(RunStatus.Errored);
            } else if (topicName.equals(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC) && action.equals(DEFAULT_START_ACTION)) {
                logger.atError().kv("lifecycle", topicName).log("Start intent action is used to shutdown service");
                return new ReadParametersResult(RunStatus.Errored);
            }
        }
        return new ReadParametersResult(RunStatus.OK, packageName, className, action);
    }

    private RunStatus foregroundServiceWork(String topicName) {
        ReadParametersResult result = readParameters(topicName, FOREGROUND_SERVICE);
        if (result.status != RunStatus.OK) {
            return result.status;
        }

        try {
            if (topicName.equals(LIFECYCLE_STARTUP_NAMESPACE_TOPIC)) {
                platform.getAndroidComponentManager().startService(result.packageName,
                        result.className, result.action);
            } else {
                platform.getAndroidComponentManager().stopService(result.packageName,
                        result.className, result.action);
            }
        } catch (IOException e) {
            logger.atError().setCause(e).kv("lifecycle", topicName).log("Failed to send intent to Android component");
            return RunStatus.Errored;
        }

        return RunStatus.OK;
    }

    private RunStatus activityWork(String topicName) {
        ReadParametersResult result = readParameters(topicName, ACTIVITY_TOPIC);
        if (result.status != RunStatus.OK) {
            return result.status;
        }

        try {
            if (topicName.equals(LIFECYCLE_RUN_NAMESPACE_TOPIC)) {
                platform.getAndroidComponentManager().startActivity(result.packageName,
                        result.className, result.action);
            } else {
                platform.getAndroidComponentManager().stopActivity(result.packageName,
                        result.className, result.action);
            }
        } catch (IOException e) {
            logger.atError().setCause(e).kv("lifecycle", topicName).log("Failed to send intent to Android component");
            return RunStatus.Errored;
        }
        return RunStatus.OK;
    }

    private String getComponentName() {
        return config.getName();
    }

    public void componentRunning() {
        reportState(State.RUNNING);
    }

    /**
     * Update field isComponentShutdown.
     */
    public void componentFinished() {
        if (getState() == State.RUNNING) {
            // FIXME: android: do we need to handle when the component stops itself?
            // bg: yes, we definitely should handle by track component status and shutdown 
            //   component when greengrass stopping for example.
            //  In GenericExternalService that do by track PIDs of child see
            //   systemResourceController.addComponentProcess(this, result.getRight().getProcess());
        } else {
            isComponentShutdown = true;
        }
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    private RunStatus installApk(String topicName) {
        // FIXME: move to separate method to easy move to bootstrap()
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(topicName);
        if (!(n instanceof Topics)) {
            logger.atDebug().kv("lifecycle", topicName).log("{} is not required: service lifecycle {} not found", 
                    topicName, topicName);
            return RunStatus.NothingDone;
        }

        Node node = ((Topics) n).getChild(APK_INSTALL_TOPIC);
        if (!(node instanceof Topics)) {
            // "APKInstall" does not exists
            logger.atError().kv("lifecycle", topicName).log("{} not found", APK_INSTALL_TOPIC);
            return RunStatus.Errored;
        }

        String packageName = Coerce.toString(getComponentName());

        String file = Coerce.toString(((Topics) node).getChild(FILE_TOPIC));
        if (file == null || file.isEmpty()) {
            logger.atError().kv("lifecycle", topicName).log("{} not found", FILE_TOPIC);
            return RunStatus.Errored;
        }

        boolean forceInstall = Coerce.toBoolean(((Topics) node).getChild(FORCE_TOPIC));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<Boolean> future = executor.submit(() -> {
                try {
                    platform.getAndroidPackageManager().installAPK(file, packageName, forceInstall);
                    context.get(ComponentManager.class).setAPKInstalled(packageName);
                    return true;
                } catch (IOException | InterruptedException e) {
                    logger.atError().kv("lifecycle", topicName).setCause(e).log("Failed to install package");
                }
                return false;
            });
        Boolean installed = false;
        try {
            while (getState() == State.NEW) {
                try {
                    installed = future.get(1, TimeUnit.SECONDS);
                    break;
                } catch (TimeoutException e) {
                }
            }
        } catch (ExecutionException | InterruptedException e) {
            future.cancel(true);
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(3,  TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                logger.atWarn().kv("lifecycle", topicName).setCause(e)
                        .log("Interrupted when waiting for cancel APK installation");
            }
        }

        return Boolean.TRUE.equals(installed) ? RunStatus.OK : RunStatus.NothingDone;
    }
}
