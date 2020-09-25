/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.ipc.AuthenticationHandler;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Pair;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;

public class GenericExternalService extends GreengrassService {
    public static final String LIFECYCLE_RUN_NAMESPACE_TOPIC = "run";
    public static final int DEFAULT_BOOTSTRAP_TIMEOUT_SEC = 120;    // 2 min
    static final String[] sigCodes =
            {"SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGIOT", "SIGBUS", "SIGFPE", "SIGKILL", "SIGUSR1",
                    "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM", "SIGTERM", "SIGSTKFLT", "SIGCHLD", "SIGCONT", "SIGSTOP",
                    "SIGTSTP", "SIGTTIN", "SIGTTOU", "SIGURG", "SIGXCPU", "SIGXFSZ", "SIGVTALRM", "SIGPROF", "SIGWINCH",
                    "SIGIO", "SIGPWR", "SIGSYS",};
    private static final String SKIP_COMMAND_REGEX = "(exists|onpath) +(.+)";
    private static final Pattern skipcmd = Pattern.compile(SKIP_COMMAND_REGEX);
    private final List<Exec> lifecycleProcesses = new CopyOnWriteArrayList<>();

    /**
     * Create a new GenericExternalService.
     *
     * @param c root topic for this service.
     */
    public GenericExternalService(Topics c) {
        this(c, c.lookupTopics(PRIVATE_STORE_NAMESPACE_TOPIC));
    }

    protected GenericExternalService(Topics c, Topics privateSpace) {
        super(c, privateSpace);

        // when configuration reloads and child Topic changes, restart/re-install the service.
        c.subscribe((what, child) -> {
            // When the service is removed via a deployment this topic itself will be removed
            // When first initialized, the child will be null
            if (WhatHappened.removed.equals(what) || child == null) {
                return;
            }

            logger.atInfo("service-config-change").kv("configNode", child.getFullName()).log();
            if (child.childOf(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC)) {
                return;
            }

            // Reinstall for changes to the install script or if the package version changed
            if (child.childOf(Lifecycle.LIFECYCLE_INSTALL_NAMESPACE_TOPIC) || child.childOf(VERSION_CONFIG_KEY)) {
                requestReinstall();
                return;
            }

            // Restart service for changes to the lifecycle config or if environment variables changed
            if (child.childOf(SERVICE_LIFECYCLE_NAMESPACE_TOPIC) || child.childOf(SETENV_CONFIG_NAMESPACE)) {
                requestRestart();
            }
        });

    }

    public static String exit2String(int exitCode) {
        return exitCode > 128 && exitCode < 129 + sigCodes.length ? sigCodes[exitCode - 129]
                : "exit(" + ((exitCode << 24) >> 24) + ")";
    }

    @Override
    public void postInject() {
        // Register token before calling super so that the token is available when the lifecyle thread
        // starts running
        AuthenticationHandler.registerAuthenticationToken(this);
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
        if (!newServiceLifecycle.containsKey(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC)
                || newServiceLifecycle.get(Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC) == null) {
            logger.atDebug().log("Bootstrap is not required: service lifecycle bootstrap not found");
            return false;
        }

        if (!getConfig().find(VERSION_CONFIG_KEY).getOnce().equals(newServiceConfig.get(VERSION_CONFIG_KEY))) {
            logger.atDebug().log("Bootstrap is required: service version changed");
            return true;
        }
        Node serviceOldBootstrap = getConfig().findNode(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC);
        boolean bootstrapStepChanged =  serviceOldBootstrap == null
                || !serviceOldBootstrap.toPOJO().equals(newServiceLifecycle.get(
                Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC));
        logger.atDebug().log(String.format("Bootstrap is %srequired: bootstrap step %schanged",
                bootstrapStepChanged ? "" : "not ", bootstrapStepChanged ? "" : "un"));
        return bootstrapStepChanged;
    }

    @Override
    protected synchronized void install() throws InterruptedException {
        stopAllLifecycleProcesses();

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
                logger.atInfo().kv("exitCode", exit).log("Startup script exited");
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
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private synchronized void handleRunScript() throws InterruptedException {
        stopAllLifecycleProcesses();
        long startingStateGeneration = getStateGeneration();

        Pair<RunStatus, Exec> result = run(LIFECYCLE_RUN_NAMESPACE_TOPIC, exit -> {
            // Synchronize within the callback so that these reportStates don't interfere with
            // the reportStates outside of the callback
            synchronized (this) {
                logger.atInfo().kv("exitCode", exit).log("Run script exited");
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
        } else {
            reportState(State.RUNNING);
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
        try {
            run(Lifecycle.LIFECYCLE_SHUTDOWN_NAMESPACE_TOPIC, null, lifecycleProcesses);
        } catch (InterruptedException ex) {
            logger.atWarn("generic-service-shutdown").log("Thread interrupted while shutting down service");
        } finally {
            stopAllLifecycleProcesses();
            logger.atInfo().setEventType("generic-service-shutdown").log();
        }
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
        // A placeholder for error handling in GenericExternalService
        run("recover", null, lifecycleProcesses);
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
            return run((Topic) n, Coerce.toString(((Topic) n).getOnce()), background, trackingList);
        }
        if (n instanceof Topics) {
            return run((Topics) n, background, trackingList);
        }
        return new Pair<>(RunStatus.NothingDone, null);
    }

    @SuppressWarnings("PMD.CloseResource")
    protected Pair<RunStatus, Exec> run(Topic t, String cmd, IntConsumer background, List<Exec> trackingList)
            throws InterruptedException {
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

    protected Pair<RunStatus, Exec> run(Topics t, IntConsumer background, List<Exec> trackingList)
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
            return run((Topic) script, Coerce.toString(((Topic) script).getOnce()), background, trackingList);
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
            Matcher m = skipcmd.matcher(expr);
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

    private void addEnv(Exec exec, Topics src) {
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
}
