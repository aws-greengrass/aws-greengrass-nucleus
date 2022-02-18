/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.TIMEOUT_NAMESPACE_TOPIC;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.LogEventBuilder;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import lombok.AllArgsConstructor;

public class AndroidExternalService extends GenericExternalService {
    // items of recipe
    public static final String FOREGROUND_SERVICE = "ForegroundService";
    public static final String APK_INSTALL = "APKInstall";
    public static final String FILE = "File";
    public static final String ACTIVITY = "Activity";
    public static final String CLASS = "Class";
    public static final String INTENT = "Intent";

    /** default class name of Activity
     */
    public static final String DEFAULT_ACTIVITY_CLASSNAME = ".DefaultGreengrassComponentActivity";

    /** default class name of ForegroundService
     */
    public static final String DEFAULT_SERVICE_CLASSNAME = ".DefaultGreengrassComponentService";

    /** default start Intent action
     */
    public static final String DEFAULT_START_ACTION = "com.aws.greengrass.START_COMPONENT";

    /** default stop Intent action
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
        //FIXME: re-implement install to use AndroidComponentExec or implement custom Exec variation to handle install/uninstall of Android components
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
        //FIXME: re-implement startup to use AndroidComponentExec
//        long startingStateGeneration = getStateGeneration();
//
//        RunStatus result = foregroundServiceWork(LIFECYCLE_STARTUP_NAMESPACE_TOPIC);
//
//        if (result == RunStatus.Errored) {
//            serviceErrored("Android component errored in startup");
//        } else if (result == RunStatus.NothingDone && startingStateGeneration == getStateGeneration()
//                && State.STARTING.equals(getState())) {
//            handleActivity();
//        }

        stopAllLifecycleProcesses();

        long startingStateGeneration = getStateGeneration();

        Pair<RunStatus, Exec> result = run(Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC, exit -> {
            // Synchronize within the callback so that these reportStates don't interfere with
            // the reportStates outside of the callback
            synchronized (this) {
                logger.atInfo().kv(EXIT_CODE, exit).log("Startup script exited");
                separateLogger.atInfo().kv(EXIT_CODE, exit).log("Startup script exited");
                State state = getState();
                if (startingStateGeneration == getStateGeneration()
                        && State.STARTING.equals(state) || State.RUNNING.equals(state)) {
                    if (exit == 0 && State.STARTING.equals(state)) {
                        reportState(State.RUNNING);
                    } else if (exit != 0) {
                        serviceErrored("Non-zero exit code in startup");
                    }
                }
            }
        }, lifecycleProcesses);

        if (result.getLeft() == RunStatus.Errored) {
            serviceErrored("Script errored in startup");
        } else if (result.getLeft() == RunStatus.NothingDone && startingStateGeneration == getStateGeneration()
                && State.STARTING.equals(getState())) {
            //FIXME: implement analog of handleRunScript();
            super.handleRunScript();
        } else if (result.getRight() != null) {
            //FIXME: implement analog
//            updateSystemResourceLimits();
//            systemResourceController.addComponentProcess(this, result.getRight().getProcess());
        }

        // FIXME: do something similar to systemResourceController.addComponentProcess(this, result.getRight().getProcess());
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
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC);
        if (n instanceof Topics) {
            Node serviceNode = ((Topics) n).getChild(FOREGROUND_SERVICE);
            Node activityNode = ((Topics) n).getChild(ACTIVITY);
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
    };

    private ReadParametersResult readParameters(String topicName, String subTopicName) {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(topicName);
        if (! (n instanceof Topics) ) {
            // "startup" or "shutdown" does not exist
            logger.atDebug().kv("lifecycle", topicName).log("{} is not required: service {} lifecycle not found", topicName, topicName);
            return new ReadParametersResult(RunStatus.NothingDone);
        }

        Node node = ((Topics) n).getChild(subTopicName);
        if (! (node instanceof Topics)) {
            // "ForegroundService" does not exists
            logger.atError().kv("lifecycle", topicName).log("ForegroundService not found");
            return new ReadParametersResult(RunStatus.Errored);
        }

        String packageName = Coerce.toString(getComponentName());

        String className = Coerce.toString(((Topics) node).getChild(CLASS));
        if (className == null || className.isEmpty()) {
            className = DEFAULT_SERVICE_CLASSNAME;
        }

        // prepend ".className" string with package name
        if (className.startsWith(".")) {
            className = packageName + className;
        }

        String action = Coerce.toString(((Topics) node).getChild(INTENT));
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
        } catch(IOException e) {
            logger.atError().setCause(e).kv("lifecycle", topicName).log("Failed to send intent to Android component");
            return RunStatus.Errored;
        }

        return RunStatus.OK;
    }

    private RunStatus activityWork(String topicName) {
        ReadParametersResult result = readParameters(topicName, ACTIVITY);
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
        } catch(IOException e) {
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

    public void componentFinished() {
        if (getState() == State.RUNNING) {
            // FIXME: android: do we need to handle when the component stops itself?
            // bg: yes, we definitely should handle by track component status and shutdown component when greengrass stopping for example.
            //  In GenericExternalService that do by track PIDs of child see
            //   systemResourceController.addComponentProcess(this, result.getRight().getProcess());
        } else {
            isComponentShutdown = true;
        }
    }

    private RunStatus installApk(String topicName) {
        // FIXME: move to separate method to easy move to bootstrap()
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(topicName);
        if (! (n instanceof Topics)) {
            logger.atDebug().kv("lifecycle", topicName).log(topicName + " is not required: service lifecycle not found");
            return RunStatus.NothingDone;
        }

        Node node = ((Topics) n).getChild(APK_INSTALL);
        if (! (node instanceof Topics)) {
            // "APKInstall" does not exists
            logger.atError().kv("lifecycle", topicName).log("APKInstall not found");
            return RunStatus.Errored;
        }

        String packageName = Coerce.toString(getComponentName());

        String file = Coerce.toString(((Topics) node).getChild(FILE));
        if (file == null || file.isEmpty()) {
            logger.atError().kv("lifecycle", topicName).log("File not found");
            return RunStatus.Errored;
        }

        /* FIXME: should be reworked to move all installation logic to single method of AndroidPackageManager
         must
         1. check version and package from file
         2. compare to package to packageName
         3. check is version of package already installed
         4. if not install in limited time
         5. if something wrong trigger an error
        */
        long packageLastUpdateTime = platform.getAndroidPackageManager().getPackageLastUpdateTime(packageName);
        logger.atDebug().kv("packageName", packageName).kv("packageLastUpdateTime", packageLastUpdateTime).log();
        boolean startedInstall = platform.getAndroidPackageManager().installPackage(file, packageName);
        logger.atDebug().kv("packageName", packageName).kv("startedInstall", startedInstall).log();
        if (startedInstall) {
            // FIXME: remove busy loop and polling
            while (getState() == State.NEW) {
                boolean isPackageInstalled = platform.getAndroidPackageManager().isPackageInstalled(packageName, packageLastUpdateTime);
                logger.atDebug().kv("packageName", packageName).kv("isPackageInstalled", isPackageInstalled).log();
                if (isPackageInstalled) {
                    return RunStatus.OK;
                } else {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } else {
            return RunStatus.NothingDone;
        }
        return RunStatus.Errored;
    }

    @Override
    protected Exec addUser(Exec exec, boolean requiresPrivilege) {
        //TODO: consider assigning privileged (root) user when Nucleus will be a system service
        logger.atWarn("Setting a user to run the component is not supported on Android");
        return exec;
    }

    @Override
    protected Pair<RunStatus, Exec> run(Topic t, String cmd, IntConsumer background, List<Exec> trackingList,
                                        boolean requiresPrivilege) throws InterruptedException {
        if (runWith == null) {
            Optional<RunWith> opt = computeRunWithConfiguration();
            if (!opt.isPresent()) {
                logger.atError().log("Could not determine user/group to run with. Ensure that {} is set for {}",
                        DeviceConfiguration.RUN_WITH_TOPIC, deviceConfiguration.getNucleusComponentName());
                return new Pair<>(RunStatus.Errored, null);
            }

            runWith = opt.get();

            LogEventBuilder logEvent = logger.atDebug().kv("user", runWith.getUser());
            if (runWith.getGroup() != null) {
                logEvent.kv("group", runWith.getGroup());
            }
            if (runWith.getShell() != null) {
                logEvent.kv("shell", runWith.getShell());
            }
            logEvent.log("Saving user information for service execution");

            if (!updateComponentPathOwner()) {
                logger.atError().log("Service artifacts may not be accessible to user");
            }
        }

        final AndroidRunner androidRunner = context.get(AndroidRunner.class);
        Exec exec;
        try {
            exec = androidRunner.setup(t.getFullName(), cmd, this);
        } catch (IOException e) {
            logger.atError().log("Error setting up to run {}", t.getFullName(), e);
            return new Pair<>(RunStatus.Errored, null);
        }
        if (exec == null) {
            return new Pair<>(RunStatus.NothingDone, null);
        }
        //FIXME: addUser & addShell may be not supported on Android
//        exec = addUser(exec, requiresPrivilege);
//        exec = addShell(exec);

        addEnv(exec, t.parent);
        logger.atDebug().setEventType("generic-service-run").log();

        // Track all running processes that we fork
        if (exec.isRunning()) {
            trackingList.add(exec);
        }
        RunStatus ret =
                androidRunner.successful(exec, t.getFullName(), background, this) ? RunStatus.OK : RunStatus.Errored;
        return new Pair<>(ret, exec);
    }

}
