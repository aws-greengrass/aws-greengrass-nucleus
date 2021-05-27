/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceException;
import com.aws.greengrass.logging.api.LogEventBuilder;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.SystemResourceController;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;

public class GenericExternalService extends GreengrassService {
    public static final String LIFECYCLE_RUN_NAMESPACE_TOPIC = "run";
    public static final int DEFAULT_BOOTSTRAP_TIMEOUT_SEC = 120;    // 2 min
    protected static final String EXIT_CODE = "exitCode";
    private static final String SKIP_COMMAND_REGEX = "(exists|onpath) +(.+)";
    private static final Pattern SKIPCMD = Pattern.compile(SKIP_COMMAND_REGEX);
    // Logger which write to a file for just this service
    protected final Logger separateLogger;
    protected final Platform platform;
    private final SystemResourceController systemResourceController;
    private final List<Exec> lifecycleProcesses = new CopyOnWriteArrayList<>();
    @Inject
    protected DeviceConfiguration deviceConfiguration;
    @Inject
    protected Kernel kernel;
    @Inject
    protected RunWithPathOwnershipHandler ownershipHandler;
    protected RunWith runWith;

    private final AtomicBoolean paused = new AtomicBoolean();

    /**
     * Create a new GenericExternalService.
     *
     * @param c root topic for this service.
     */
    public GenericExternalService(Topics c) {
        this(c, Platform.getInstance());
    }

    /**
     * Create a new GenericExternalService.
     *
     * @param c root topic for this service.
     * @param platform the platform instance to use.
     */
    public GenericExternalService(Topics c, Platform platform) {
        this(c, c.lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC), platform);
    }

    protected GenericExternalService(Topics c, Topics privateSpace) {
        this(c, privateSpace, Platform.getInstance());
    }

    protected GenericExternalService(Topics c, Topics privateSpace, Platform platform) {
        super(c, privateSpace);
        this.platform = platform;
        this.systemResourceController = platform.getSystemResourceController();

        this.separateLogger = LogManagerHelper.getComponentLogger(this).createChild();
        separateLogger.dfltKv(SERVICE_NAME_KEY, getServiceName());
        separateLogger.dfltKv(CURRENT_STATE_METRIC_NAME, (Supplier<State>) this::getState);

        // when configuration reloads and child Topic changes, restart/re-install the service.
        c.subscribe((what, child) -> {
            // When the service is removed via a deployment this topic itself will be removed
            // When first initialized, the child will be null
            if (WhatHappened.removed.equals(what) || child == null
                    || WhatHappened.timestampUpdated.equals(what) || WhatHappened.interiorAdded.equals(what)) {
                return;
            }

            if (child.childOf(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC)) {
                return;
            }

            if (!WhatHappened.initialized.equals(what) && child.childOf(SYSTEM_RESOURCE_LIMITS_TOPICS)) {
                updateSystemResourceLimits();
            }

            // Reinstall for changes to the install script or if the package version changed, or posixUser has changed
            if (child.childOf(Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC) || child.childOf(VERSION_CONFIG_KEY)
                    || child.childOf(POSIX_USER_KEY)) {
                logger.atInfo("service-config-change").kv("configNode", child.getFullName())
                        .log("Requesting reinstallation for component");
                requestReinstall();
                return;
            }

            // Restart service for changes to the lifecycle config or environment variables
            if (child.childOf(SERVICE_LIFECYCLE_NAMESPACE_TOPIC) || child.childOf(SETENV_CONFIG_NAMESPACE)) {
                logger.atInfo("service-config-change").kv("configNode", child.getFullName())
                        .log("Requesting restart for component");
                requestRestart();
            }
        });
    }

    private void updateSystemResourceLimits() {
        Topics systemResourceLimits = config.findTopics(RUN_WITH_NAMESPACE_TOPIC,
                        SYSTEM_RESOURCE_LIMITS_TOPICS, PlatformResolver.getOSInfo());
        if (systemResourceLimits == null) {
            systemResourceLimits = deviceConfiguration.findRunWithDefaultSystemResourceLimits();
        }

        if (systemResourceLimits == null) {
            systemResourceController.resetResourceLimits(this);
        } else {
            Map<String, Object> resourceLimits = new HashMap<>();
            resourceLimits.putAll(systemResourceLimits.toPOJO());
            systemResourceController.updateResourceLimits(this, resourceLimits);
        }
    }

    /**
     * Check if the case-insensitive lifecycle key is defined in the service lifecycle configuration map.
     *
     * @param newServiceLifecycle service lifecycle configuration map
     * @param lifecycleKey        case-insensitive lifecycle key
     * @return key in the map that matches the lifecycle key; empty string if no match
     */
    public static String serviceLifecycleDefined(Map<String, Object> newServiceLifecycle, String lifecycleKey) {
        for (Map.Entry<String, Object> entry : newServiceLifecycle.entrySet()) {
            if (lifecycleKey.equalsIgnoreCase(entry.getKey()) && Objects.nonNull(entry.getValue())) {
                return entry.getKey();
            }
        }
        return "";
    }

    @Override
    public void postInject() {
        // Register token before calling super so that the token is available when the lifecyle thread
        // starts running
        AuthenticationHandler.registerAuthenticationToken(this);
        // Update the system resource limits if the default system resource limits has changed.
        deviceConfiguration.getRunWithTopic().subscribe((what, child) -> {
            if (!WhatHappened.initialized.equals(what) && child != null
                    && child.childOf(SYSTEM_RESOURCE_LIMITS_TOPICS)) {
                updateSystemResourceLimits();
            }
        });
        super.postInject();
    }

    /**
     * Run the command under 'bootstrap' and returns the exit code. The timeout can be configured with 'timeout' field
     * in seconds. If not configured, by default, it times out after 2 minutes.
     *
     * @return exit code of process
     * @throws InterruptedException when the command execution is interrupted.
     * @throws TimeoutException     when the command execution times out.
     */
    @Override
    @SuppressFBWarnings(value = {"RCN_REDUNDANT_NULLCHECK_OF_NULL_VALUE", "NP_LOAD_OF_KNOWN_NULL_VALUE"},
            justification = "Known false-positives")
    public synchronized int bootstrap() throws InterruptedException, TimeoutException {
        // this is redundant because all lifecycle processes should have been before calling this method.
        // stopping here again to be safer
        stopAllLifecycleProcesses();

        CountDownLatch timeoutLatch = new CountDownLatch(1);
        AtomicInteger atomicExitCode = new AtomicInteger();

        // run the command at background thread so that the main thread can handle it when it times out
        // note that this could be a foreground process but it requires run() methods, ShellerRunner, and Exec's method
        // signature changes to deal with timeout, so we decided to go with background thread.
        Pair<RunStatus, Exec> pair = run(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, exitCode -> {
            atomicExitCode.set(exitCode);
            timeoutLatch.countDown();
        }, lifecycleProcesses);
        try (Exec exec = pair.getRight()) {
            if (exec == null) {
                if (pair.getLeft() == RunStatus.Errored) {
                    return 1;
                }
                // no bootstrap command found
                return 0;
            }

            // timeout handling
            int timeoutInSec = (int) config
                    .findOrDefault(DEFAULT_BOOTSTRAP_TIMEOUT_SEC, SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                            Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, Lifecycle.TIMEOUT_NAMESPACE_TOPIC);
            boolean completedInTime = timeoutLatch.await(timeoutInSec, TimeUnit.SECONDS);
            if (!completedInTime) {
                String msg = String.format("Bootstrap step timed out after '%d' seconds.", timeoutInSec);
                throw new TimeoutException(msg);
            }

        } catch (IOException e) {
            logger.atError("bootstrap-process-close-error").setCause(e).log("Error closing process at bootstrap step.");
            // No need to return special error code here because the exit code is handled by atomicExitCode.
        }

        return atomicExitCode.get();
    }

    private boolean isPrivilegeRequired(String lifecycleName) {
        return Coerce.toBoolean(config.findOrDefault(false, SERVICE_LIFECYCLE_NAMESPACE_TOPIC, lifecycleName,
                Lifecycle.REQUIRES_PRIVILEGE_NAMESPACE_TOPIC));
    }

    /**
     * Check if bootstrap step needs to run during service update. Called during deployments to determine deployment
     * workflow.
     *
     * @param newServiceConfig new service config for the update
     * @return true if the service
     *      1. has a bootstrap step defined, 2. component version changes, or bootstrap step changes.
     *      false otherwise
     */
    @Override
    public boolean isBootstrapRequired(Map<String, Object> newServiceConfig) {
        if (newServiceConfig == null || !newServiceConfig.containsKey(SERVICE_LIFECYCLE_NAMESPACE_TOPIC)) {
            logger.atDebug().log("Bootstrap is not required: service lifecycle config not found");
            return false;
        }
        Map<String, Object> newServiceLifecycle =
                (Map<String, Object>) newServiceConfig.get(SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        String lifecycleKey = serviceLifecycleDefined(newServiceLifecycle,
                Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC);
        if (lifecycleKey.isEmpty()) {
            logger.atDebug().log("Bootstrap is not required: service lifecycle bootstrap not found");
            return false;
        }

        if (!getConfig().find(VERSION_CONFIG_KEY).getOnce().equals(newServiceConfig.get(VERSION_CONFIG_KEY))) {
            logger.atDebug().log("Bootstrap is required: service version changed");
            return true;
        }
        Node serviceOldBootstrap = getConfig().findNode(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC);
        boolean bootstrapStepChanged = serviceOldBootstrap == null || !serviceLifecycleBootstrapEquals(
                serviceOldBootstrap.toPOJO(), newServiceLifecycle.get(lifecycleKey));
        if (bootstrapStepChanged) {
            logger.atDebug().kv("before",
                    (Supplier<Object>) () -> serviceOldBootstrap == null ? null : serviceOldBootstrap.toPOJO())
                    .kv("after", newServiceLifecycle.get(lifecycleKey))
                    .log("Bootstrap is required: bootstrap step changed");
        } else {
            logger.atDebug().log("Bootstrap is not required: bootstrap step unchanged");
        }
        return bootstrapStepChanged;
    }

    private boolean serviceLifecycleBootstrapEquals(Object before, Object after) {
        if (!(before instanceof Map) || !(after instanceof Map)) {
            return Objects.equals(before, after);
        }
        Map<String, Object> beforeMap = (Map<String, Object>) before;
        Map<String, Object> afterMap = (Map<String, Object>) after;
        if (beforeMap.size() != afterMap.size()) {
            return false;
        }
        for (Map.Entry<String, Object> beforeEntry : beforeMap.entrySet()) {
            CaseInsensitiveString key = new CaseInsensitiveString(beforeEntry.getKey());
            boolean keyFound = false;
            for (Map.Entry<String, Object> entry : afterMap.entrySet()) {
                if (key.equals(new CaseInsensitiveString(entry.getKey())) && Objects.nonNull(entry.getValue())) {
                    keyFound = true;
                    if (!Objects.equals(entry.getValue().toString(), beforeEntry.getValue().toString())) {
                        return false;
                    }
                }
            }
            if (!keyFound) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("PMD.NullAssignment")
    void resetRunWith() {
        runWith = null;
    }

    @Override
    protected synchronized void install() throws InterruptedException {
        stopAllLifecycleProcesses();

        // reset runWith in case we moved from NEW -> INSTALLED -> change runwith -> NEW
        resetRunWith();

        if (run(Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC, null, lifecycleProcesses).getLeft() == RunStatus.Errored) {
            serviceErrored("Script errored in install");
        }
    }

    // Synchronize startup() and shutdown() as both are non-blocking, but need to have coordination
    // to operate properly
    @Override
    protected synchronized void startup() throws InterruptedException {
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
            handleRunScript();
        } else if (result.getRight() != null) {
            systemResourceController.addComponentProcess(this, result.getRight().getProcess());
        }
    }

    /**
     * Pause a running component.
     *
     * @throws ServiceException Error processing pause request.
     */
    public synchronized void pause() throws ServiceException {
        logger.atDebug().log("Pausing running component");
        if (paused.get()) {
            return;
        }
        try {
            List<Process> processes = lifecycleProcesses.stream().map(Exec::getProcess).collect(Collectors.toList());
            systemResourceController.pauseComponentProcesses(this, processes);
            paused.set(true);
            logger.atDebug().log("Paused component");
        } catch (IOException e) {
            logger.atError().setCause(e).log("Error pausing component");
            throw new ServiceException(String.format("Error pausing component %s", getServiceName()), e);
        }
    }

    /**
     * Resume a paused component.
     *
     * @throws ServiceException Error processing resume request.
     */
    public synchronized void resume() throws ServiceException {
        resume(true, true);
    }

    private synchronized void resume(boolean restartOnFail, boolean retryOnFail) throws ServiceException {
        logger.atDebug().log("Resuming component");
        if (paused.get()) {
            int retryAttempts = 3;
            while (true) {
                retryAttempts--;
                try {
                    systemResourceController.resumeComponentProcesses(this);
                    paused.set(false);
                    logger.atDebug().log("Resumed component");
                    return;
                } catch (IOException e) {
                    if (retryOnFail && retryAttempts > 0) {
                        logger.atInfo().setCause(e).log("Error resuming component, retrying");
                    } else {
                        logger.atError().setCause(e).log("Error resuming component and all retried exhausted, "
                                + "restarting");
                        if (restartOnFail) {
                            // Reset tracking flag
                            paused.set(false);
                            requestRestart();
                        }
                        throw new ServiceException(String.format("Error resuming component %s",
                                getServiceName()), e);
                    }
                }
            }
        }
    }

    /**
     * Check if component is paused.
     * @return true if paused
     */
    public boolean isPaused() {
        return paused.get();
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized void handleRunScript() throws InterruptedException {
        stopAllLifecycleProcesses();
        long startingStateGeneration = getStateGeneration();

        Pair<RunStatus, Exec> result = run(LIFECYCLE_RUN_NAMESPACE_TOPIC, exit -> {
            // Synchronize within the callback so that these reportStates don't interfere with
            // the reportStates outside of the callback
            synchronized (this) {
                logger.atInfo().kv(EXIT_CODE, exit).log("Run script exited");
                separateLogger.atInfo().kv(EXIT_CODE, exit).log("Run script exited");
                if (startingStateGeneration == getStateGeneration() && currentOrReportedStateIs(State.RUNNING)) {
                    if (exit == 0) {
                        logger.atInfo().setEventType("generic-service-stopping").log("Service finished running");
                        this.requestStop();
                    } else {
                        reportState(State.ERRORED);
                    }
                }
            }
        }, lifecycleProcesses);

        if (result.getLeft() == RunStatus.NothingDone) {
            reportState(State.FINISHED);
            logger.atInfo().setEventType("generic-service-finished").log("Nothing done");
            return;
        } else if (result.getLeft() == RunStatus.Errored) {
            serviceErrored("Script errored in run");
            return;
        } else if (result.getRight() != null) {
            reportState(State.RUNNING);
            systemResourceController.addComponentProcess(this, result.getRight().getProcess());
        }

        Topic timeoutTopic = config.find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC,
                        Lifecycle.TIMEOUT_NAMESPACE_TOPIC);
        Integer timeout = timeoutTopic == null ? null : (Integer) timeoutTopic.getOnce();
        if (timeout != null) {
            Exec processToClose = result.getRight();
            context.get(ScheduledExecutorService.class).schedule(() -> {
                if (processToClose.isRunning()) {
                    try {
                        logger.atWarn("service-run-timed-out")
                                .log("Service failed to run within timeout, calling close in process");
                        reportState(State.ERRORED);
                        processToClose.close();
                    } catch (IOException e) {
                        logger.atError("service-close-error").setCause(e)
                                .log("Error closing service after run timed out");
                    }
                }
            }, timeout, TimeUnit.SECONDS);
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

        try {
            run(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, null, lifecycleProcesses);
        } catch (InterruptedException ex) {
            logger.atWarn("generic-service-shutdown").log("Thread interrupted while shutting down service");
        } finally {
            stopAllLifecycleProcesses();

            // Clean up any resource manager entities (can be OS specific) that might have been created for this
            // component.
            systemResourceController.removeResourceController(this);

            logger.atInfo().setEventType("generic-service-shutdown").log();
        }
        resetRunWith(); // reset runWith - a deployment can change user info
    }

    private synchronized void stopAllLifecycleProcesses() {
        stopProcesses(lifecycleProcesses);
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized void stopProcesses(List<Exec> processes) {
        for (Exec e : processes) {
            if (e != null && e.isRunning()) {
                logger.atInfo().log("Shutting down process {}", e);
                try {
                    e.close();
                    logger.atInfo().log("Shutdown completed for process {}", e);
                    processes.remove(e);
                } catch (IOException ex) {
                    logger.atWarn().log("Shutdown timed out for process {}", e);
                }
            } else {
                processes.remove(e);
            }
        }
    }

    @Override
    public void handleError() throws InterruptedException {
        if (getConfig().findNode(GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                Lifecycle.LIFECYCLE_RECOVER_NAMESPACE_TOPIC) == null) {
            // No recovery step defined in lifecycle
            return;
        }

        int timeout = Coerce.toInt(getConfig().findOrDefault(Lifecycle.DEFAULT_ERROR_RECOVERY_HANDLER_TIMEOUT_SEC,
                GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC, Lifecycle.LIFECYCLE_RECOVER_NAMESPACE_TOPIC,
                Lifecycle.TIMEOUT_NAMESPACE_TOPIC));

        CountDownLatch handlerExecutionCdl = new CountDownLatch(1);
        run(Lifecycle.LIFECYCLE_RECOVER_NAMESPACE_TOPIC, c -> handlerExecutionCdl.countDown(), lifecycleProcesses);

        if (!handlerExecutionCdl.await(timeout, TimeUnit.SECONDS)) {
            logger.atError().log(String.format("Error recovery handler timed out after %d seconds", timeout));
        }
    }

    /**
     * Computer user, group, and shell that will be used to run the service. This should be used throughout the
     * lifecycle.
     *
     * <p>This information can change with a deployment, but service *must* execute the lifecycle steps with the same
     * user/group/shell that was configured when it started.
     */
    protected Optional<RunWith> computeRunWithConfiguration() {
        return platform.getRunWithGenerator().generate(deviceConfiguration, config);
    }

    /**
     * Ownership of all files in the artifact and service work directory is updated to reflect the current runWithUser
     * and runWithGroup.
     *
     * @return <tt>true</tt> if the update succeeds, otherwise false.
     */
    protected boolean updateComponentPathOwner() {
        // no artifacts if no version key
        if (config.findLeafChild(VERSION_CONFIG_KEY) == null) {
            return true;
        }

        ComponentIdentifier id = ComponentIdentifier.fromServiceTopics(config);
        try {
            ownershipHandler.updateOwner(id, runWith);
            return true;
        } catch (IOException e) {
            LogEventBuilder logEvent = logger.atError()
                    .setEventType("update-artifact-owner")
                    .setCause(e)
                    .kv("user", runWith.getUser());
            if (runWith.getGroup() != null) {
                logEvent.kv("group", runWith.getGroup());
            }
            logEvent.log("Error updating service artifact owner");
            return false;
        }
    }

    /**
     * Run one of the commands defined in the config on the command line.
     *
     * @param name         name of the command to run ("run", "install", "startup", "bootstrap").
     * @param background   IntConsumer to and run the command as background process and receive the exit code. If null,
     *                     the command will run as a foreground process and blocks indefinitely.
     * @param trackingList List used to track running processes.
     * @return the status of the run and the Exec.
     */
    protected Pair<RunStatus, Exec> run(String name, IntConsumer background, List<Exec> trackingList)
            throws InterruptedException {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(name);
        if (n == null) {
            return new Pair<>(RunStatus.NothingDone, null);
        }

        if (n instanceof Topic) {
            return run((Topic) n, Coerce.toString(n), background, trackingList, isPrivilegeRequired(name));
        }
        if (n instanceof Topics) {
            return run((Topics) n, background, trackingList, isPrivilegeRequired(name));
        }
        return new Pair<>(RunStatus.NothingDone, null);
    }

    @SuppressWarnings("PMD.CloseResource")
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

        final ShellRunner shellRunner = context.get(ShellRunner.class);
        Exec exec;
        try {
            exec = shellRunner.setup(t.getFullName(), cmd, this);
        } catch (IOException e) {
            logger.atError().log("Error setting up to run {}", t.getFullName(), e);
            return new Pair<>(RunStatus.Errored, null);
        }
        if (exec == null) {
            return new Pair<>(RunStatus.NothingDone, null);
        }
        exec = addUser(exec, requiresPrivilege);
        exec = addShell(exec);

        addEnv(exec, t.parent);
        logger.atDebug().setEventType("generic-service-run").log();

        // Track all running processes that we fork
        if (exec.isRunning()) {
            trackingList.add(exec);
        }
        RunStatus ret =
                shellRunner.successful(exec, t.getFullName(), background, this) ? RunStatus.OK : RunStatus.Errored;
        return new Pair<>(ret, exec);
    }

    protected Pair<RunStatus, Exec> run(Topics t, IntConsumer background, List<Exec> trackingList,
                                        boolean requiresPrivilege)
            throws InterruptedException {
        try {
            if (shouldSkip(t)) {
                logger.atDebug().setEventType("generic-service-skipped").addKeyValue("script", t.getFullName()).log();
                return new Pair<>(RunStatus.OK, null);
            }
        } catch (InputValidationException e) {
            return new Pair<>(RunStatus.Errored, null);
        }

        Node script = t.getChild("script");
        if (script instanceof Topic) {
            return run((Topic) script, Coerce.toString(script), background, trackingList, requiresPrivilege);
        } else {
            logger.atError().setEventType("generic-service-invalid-config").addKeyValue("configNode", t.getFullName())
                    .log("Missing script");
            return new Pair<>(RunStatus.Errored, null);
        }
    }

    boolean shouldSkip(Topics n) throws InputValidationException {
        Node skipif = n.getChild("skipif");
        if (skipif instanceof Topic) {
            Topic tp = (Topic) skipif;
            String expr = String.valueOf(tp.getOnce()).trim();
            boolean neg = false;
            if (expr.startsWith("!")) {
                expr = expr.substring(1).trim();
                neg = true;
            }
            Matcher m = SKIPCMD.matcher(expr);
            if (m.matches()) {
                switch (m.group(1)) {
                    case "onpath":
                        return Exec.which(m.group(2)) != null ^ neg; // XOR ?!?!
                    case "exists":
                        return Files.exists(Paths.get(context.get(KernelCommandLine.class).deTilde(m.group(2)))) ^ neg;
                    default:
                        logger.atError().setEventType("generic-service-invalid-config")
                                .addKeyValue("operator", m.group(1)).log("Unknown operator in skipif");
                        throw new InputValidationException("Unknown operator in skipif");
                }
            }
            logger.atError().setEventType("generic-service-invalid-config").addKeyValue("command received", expr)
                    .addKeyValue("valid pattern", SKIP_COMMAND_REGEX)
                    .log("Invalid format for skipif. Should follow the pattern");
            throw new InputValidationException("Invalid format for skipif");
        }
        return false;
    }

    protected void addEnv(Exec exec, Topics src) {
        if (src == null) {
            return;
        }

        addEnv(exec, src.parent); // add parents contributions first
        Node env = src.getChild(SETENV_CONFIG_NAMESPACE);
        if (env instanceof Topics) {
            ((Topics) env).forEach(n -> {
                if (n instanceof Topic) {
                    exec.setenv(n.getName(), Coerce.toString(((Topic) n).getOnce()));
                }
            });
        }
    }

    protected Exec addUserGroup(Exec exec) {
        return addUserGroup(exec, runWith.getUser(), runWith.getGroup());
    }

    protected Exec addUserGroup(Exec exec, String user, String group) {
        boolean validUser = !Utils.isEmpty(user);
        if (validUser) {
            exec = exec.withUser(user);
            boolean validGroup = !Utils.isEmpty(group);
            if (validGroup) {
                exec = exec.withGroup(group);
            }
        }
        return exec;
    }

    /**
     * Add privileged user to the Exec.
     *
     * @param exec the exec to modify.
     * @return the exec.
     */
    protected Exec addPrivilegedUser(Exec exec) {
        if (Exec.isWindows) {
            logger.atWarn("Windows lifecycle steps cannot run as different users");
            return exec;
        }
        String user = Platform.getInstance().getPrivilegedUser();
        String group = Platform.getInstance().getPrivilegedGroup();
        return addUserGroup(exec, user, group);
    }

    /**
     * Add the shell saved when service initially started to the Exec.
     *
     * @param exec the Exec to modify.
     * @return the Exec
     */
    protected Exec addShell(Exec exec) {
        if (Exec.isWindows) {
            return exec;
        }
        return exec.usingShell(runWith.getShell());
    }

    /**
     * Set the user to run the command as. This loads the user that was configured when the service initially started.
     * If privilege is required for the command, the privileged user is loaded instead.
     *
     * @param exec the execution to modify.
     * @param requiresPrivilege whether the step requires privilege or not.
     * @return the exec.
     */
    protected Exec addUser(Exec exec, boolean requiresPrivilege) {
        if (requiresPrivilege) {
            exec = addPrivilegedUser(exec);
        } else {
            exec = addUserGroup(exec);
        }
        return exec;
    }
}
