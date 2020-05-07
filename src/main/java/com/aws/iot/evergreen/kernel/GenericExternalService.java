/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.AuthHandler;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Pair;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aws.iot.evergreen.kernel.Lifecycle.TIMEOUT_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

public class GenericExternalService extends EvergreenService {
    public static final String LIFECYCLE_RUN_NAMESPACE_TOPIC = "run";
    static final String[] sigCodes =
            {"SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGIOT", "SIGBUS", "SIGFPE", "SIGKILL", "SIGUSR1",
                    "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM", "SIGTERM", "SIGSTKFLT", "SIGCHLD", "SIGCONT", "SIGSTOP",
                    "SIGTSTP", "SIGTTIN", "SIGTTOU", "SIGURG", "SIGXCPU", "SIGXFSZ", "SIGVTALRM", "SIGPROF", "SIGWINCH",
                    "SIGIO", "SIGPWR", "SIGSYS",};
    private static final String SKIP_COMMAND_REGEX = "(exists|onpath) +(.+)";
    private static final Pattern skipcmd = Pattern.compile(SKIP_COMMAND_REGEX);
    public static final String SAFE_UPDATE_TOPIC_NAME = "checkIfSafeToUpdate";
    public static final String UPDATES_COMPLETED_TOPIC_NAME = "updatesCompleted";
    public static final int DEFAULT_SAFE_UPDATE_TIMEOUT = 5;
    public static final int DEFAULT_SAFE_UPDATE_RECHECK_TIME = 30;
    public static final String RECHECK_PERIOD_TOPIC_NAME = "recheckPeriod";
    private final List<Exec> lifecycleProcesses = new CopyOnWriteArrayList<>();
    private final List<Exec> safeUpdateProcesses = new CopyOnWriteArrayList<>();

    /**
     * Create a new GenericExternalService.
     *
     * @param c root topic for this service.
     */
    public GenericExternalService(Topics c) {
        super(c);

        // when configuration reloads and child Topic changes, restart/re-install the service.
        c.subscribe((what, child) -> {
            // When the service is removed via a deployment this topic itself will be removed
            // When first initialized, the child will be null
            if (WhatHappened.removed.equals(what) || child == null) {
                return;
            }

            logger.atInfo("service-config-change").kv("configNode", child.getFullName()).log();
            if (child.childOf("shutdown")) {
                return;
            }

            // Reinstall for changes to the install script or if the package version changed
            if (child.childOf("install") || child.childOf(VERSION_CONFIG_KEY)) {
                requestReinstall();
                return;
            }

            // Restart service for changes to the lifecycle config or if environment variables changed
            if (child.childOf(SERVICE_LIFECYCLE_NAMESPACE_TOPIC) || child.childOf(SETENV_CONFIG_NAMESPACE)) {
                requestRestart();
            }
        });

        AuthHandler.registerAuthToken(this);
    }

    public static String exit2String(int exitCode) {
        return exitCode > 128 && exitCode < 129 + sigCodes.length ? sigCodes[exitCode - 129]
                : "exit(" + ((exitCode << 24) >> 24) + ")";
    }

    @Override
    public synchronized void install() throws InterruptedException {
        stopAllLifecycleProcesses();

        if (run("install", null, lifecycleProcesses).getLeft() == RunStatus.Errored) {
            serviceErrored("Script errored in install");
        }
    }

    // Synchronize startup() and shutdown() as both are non-blocking, but need to have coordination
    // to operate properly
    @Override
    public synchronized void startup() throws InterruptedException {
        stopAllLifecycleProcesses();

        long startingStateGeneration = getStateGeneration();

        Pair<RunStatus, Exec> result = run(Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC, exit -> {
            // Synchronize within the callback so that these reportStates don't interfere with
            // the reportStates outside of the callback
            synchronized (this) {
                logger.atInfo().kv("exitCode", exit).log("Startup script exited");
                if (startingStateGeneration == getStateGeneration() && State.STARTING.equals(getState())) {
                    if (exit == 0) {
                        reportState(State.RUNNING);
                    } else {
                        serviceErrored("Non-zero exit code in startup");
                    }
                }
            }
        }, lifecycleProcesses);

        if (result.getLeft() == RunStatus.Errored) {
            serviceErrored("Script errored in startup");
        } else if (result.getLeft() == RunStatus.NothingDone) {
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

        Topic timeoutTopic =
                config.find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC, LIFECYCLE_RUN_NAMESPACE_TOPIC, TIMEOUT_NAMESPACE_TOPIC);
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
    public synchronized void shutdown() {
        logger.atInfo().log("Shutdown initiated");
        try {
            run("shutdown", null, lifecycleProcesses);
        } catch (InterruptedException ex) {
            logger.atWarn("generic-service-shutdown").log("Thread interrupted while shutting down service");
            return;
        }
        stopAllLifecycleProcesses();
        logger.atInfo().setEventType("generic-service-shutdown").log();
    }

    @Override
    public long whenIsDisruptionOK() {
        stopAllSafeUpdateProcesses();
        try {
            CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
            Pair<RunStatus, Exec> result = run(SAFE_UPDATE_TOPIC_NAME, exitFuture::complete, safeUpdateProcesses);

            // If we didn't do anything then it is safe to update
            if (result.getLeft().equals(RunStatus.NothingDone)) {
                return 0L;
            }

            // If it ran, then check the result or timeout
            if (result.getLeft().equals(RunStatus.OK)) {
                int timeout = Coerce.toInt(
                        config.findOrDefault(DEFAULT_SAFE_UPDATE_TIMEOUT, SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                                SAFE_UPDATE_TOPIC_NAME, TIMEOUT_NAMESPACE_TOPIC));
                try {
                    int exitCode = exitFuture.get(timeout, TimeUnit.SECONDS);
                    // Define exit code 0 to mean that it is safe to update right now
                    if (exitCode == 0) {
                        logger.atDebug().log("{} returned 0, so it is safe to update now", SAFE_UPDATE_TOPIC_NAME);
                        return 0L;
                    }
                } catch (ExecutionException e) {
                    // Not possible
                } catch (TimeoutException e) {
                    logger.atWarn()
                            .log("Timed out while running {}. Will try to update again later", SAFE_UPDATE_TOPIC_NAME);
                }
                result.getRight().close();
            }
        } catch (InterruptedException ignore) {
        } catch (IOException e) {
            logger.atWarn().log("Error while running {}. Will try to update again later", SAFE_UPDATE_TOPIC_NAME, e);
        }

        int recheckSeconds = Coerce.toInt(
                config.findOrDefault(DEFAULT_SAFE_UPDATE_RECHECK_TIME, SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                        SAFE_UPDATE_TOPIC_NAME, RECHECK_PERIOD_TOPIC_NAME));
        logger.atInfo().kv(RECHECK_PERIOD_TOPIC_NAME, recheckSeconds)
                .log("{} decided it is unsafe to update now. Will try to update again later", SAFE_UPDATE_TOPIC_NAME);

        // By default, if anything goes wrong we will assume it is not safe to update right now
        return Instant.now().plusSeconds(recheckSeconds).toEpochMilli();
    }

    @Override
    public void disruptionCompleted() {
        stopAllSafeUpdateProcesses();
        CompletableFuture<Integer> exitFuture = new CompletableFuture<>();
        try {
            Pair<RunStatus, Exec> result = run(UPDATES_COMPLETED_TOPIC_NAME, exitFuture::complete, safeUpdateProcesses);
            if (result.getLeft().equals(RunStatus.NothingDone) || result.getLeft().equals(RunStatus.Errored)) {
                return;
            }

            int timeout = Coerce.toInt(
                    config.findOrDefault(DEFAULT_SAFE_UPDATE_TIMEOUT, SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                            UPDATES_COMPLETED_TOPIC_NAME, TIMEOUT_NAMESPACE_TOPIC));
            try {
                exitFuture.get(timeout, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // Not possible
            } catch (TimeoutException e) {
                logger.atWarn().log("Timed out while running {}", UPDATES_COMPLETED_TOPIC_NAME);
            }
            result.getRight().close();
        } catch (InterruptedException ignore) {
        } catch (IOException e) {
            logger.atWarn().log("Error while running {}", UPDATES_COMPLETED_TOPIC_NAME, e);
        }
    }

    private synchronized void stopAllLifecycleProcesses() {
        stopProcesses(lifecycleProcesses);
    }

    private synchronized void stopAllSafeUpdateProcesses() {
        stopProcesses(safeUpdateProcesses);
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
        run("handleError", null, lifecycleProcesses);
    }

    /**
     * Run one of the commands defined in the config on the command line.
     *
     * @param name         name of the command to run ("run", "install", "start").
     * @param background   IntConsumer to receive the exit code. If null, the command will timeout after 2 minutes.
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
        Exec exec = shellRunner.setup(t.getFullName(), cmd, this);
        if (exec == null) {
            return new Pair<>(RunStatus.NothingDone, null);
        }
        addEnv(exec, t.parent);
        logger.atDebug().setEventType("generic-service-run").log();

        RunStatus ret =
                shellRunner.successful(exec, t.getFullName(), background, this) ? RunStatus.OK : RunStatus.Errored;

        // Track all running processes that we fork
        if (exec.isRunning()) {
            trackingList.add(exec);
        }
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
