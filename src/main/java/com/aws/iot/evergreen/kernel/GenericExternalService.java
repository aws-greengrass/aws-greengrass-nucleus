/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.ipc.AuthHandler;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.aws.iot.evergreen.kernel.Lifecycle.TIMEOUT_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.VERSION_CONFIG_KEY;

@SuppressWarnings("PMD.NullAssignment")
public class GenericExternalService extends EvergreenService {
    public static final String LIFECYCLE_RUN_NAMESPACE_TOPIC = "run";
    static final String[] sigCodes =
            {"SIGHUP", "SIGINT", "SIGQUIT", "SIGILL", "SIGTRAP", "SIGIOT", "SIGBUS", "SIGFPE", "SIGKILL", "SIGUSR1",
                    "SIGSEGV", "SIGUSR2", "SIGPIPE", "SIGALRM", "SIGTERM", "SIGSTKFLT", "SIGCHLD", "SIGCONT", "SIGSTOP",
                    "SIGTSTP", "SIGTTIN", "SIGTTOU", "SIGURG", "SIGXCPU", "SIGXFSZ", "SIGVTALRM", "SIGPROF", "SIGWINCH",
                    "SIGIO", "SIGPWR", "SIGSYS",};
    private static final String SKIP_COMMAND_REGEX = "(exists|onpath) +(.+)";
    private static final Pattern skipcmd = Pattern.compile(SKIP_COMMAND_REGEX);
    private boolean inShutdown;
    // currentScript is the Exec that's currently under executing
    private Exec currentScript;
    // runScript is the Exec to run the service and is currently under executing.
    private Exec runScript;

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
            // By default for any change, just restart the service
            requestRestart();
        });

        AuthHandler.registerAuthToken(this);
    }

    public static String exit2String(int exitCode) {
        return exitCode > 128 && exitCode < 129 + sigCodes.length ? sigCodes[exitCode - 129]
                : "exit(" + ((exitCode << 24) >> 24) + ")";
    }

    @Override
    public void install() throws InterruptedException {
        if (run("install", null) == RunStatus.Errored) {
            reportState(State.ERRORED);
        }
    }

    @Override
    public void startup() throws InterruptedException {
        RunStatus result = run(Lifecycle.LIFECYCLE_STARTUP_NAMESPACE_TOPIC, exit -> {
            runScript = null;
            if (getState() == State.INSTALLED) {
                if (exit == 0) {
                    reportState(State.RUNNING);
                } else {
                    reportState(State.ERRORED);
                }
            }
        });

        runScript = currentScript;
        if (result == RunStatus.Errored) {
            reportState(State.ERRORED);
        } else if (result == RunStatus.NothingDone) {
            handleRunScript();
        }
    }

    @SuppressWarnings("PMD.CloseResource")
    private void handleRunScript() throws InterruptedException {
        // sync block will ensure that the call back can execute only after
        // the service transition state based on RunStatus result
        Object lock = new Object();
        synchronized (lock) {
            RunStatus result = run(LIFECYCLE_RUN_NAMESPACE_TOPIC, exit -> {
                synchronized (lock) {
                    runScript = null;
                    if (!inShutdown) {
                        if (exit == 0) {
                            this.requestStop();
                            logger.atInfo().setEventType("generic-service-stopping")
                                    .log("Service finished running");
                        } else {
                            reportState(State.ERRORED);
                            logger.atError().setEventType("generic-service-errored")
                                    .addKeyValue("exitCode", exit).log();
                        }
                    }
                }
            });
            if (result == RunStatus.NothingDone) {
                reportState(State.FINISHED);
                logger.atInfo().setEventType("generic-service-finished").log("Nothing done");
            } else if (result == RunStatus.Errored) {
                reportState(State.ERRORED);
            } else {
                reportState(State.RUNNING);
                runScript = currentScript;
            }
        }

        Topic timeoutTopic = config.find(SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                LIFECYCLE_RUN_NAMESPACE_TOPIC, TIMEOUT_NAMESPACE_TOPIC);
        Integer timeout = timeoutTopic == null ? null : (Integer) timeoutTopic.getOnce();
        if (timeout != null) {
            Exec processToClose = currentScript;
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
    @SuppressWarnings("PMD.CloseResource")
    public void shutdown() {
        inShutdown = true;
        try {
            run("shutdown", null);
        } catch (InterruptedException ex) {
            inShutdown = false;
            logger.atWarn("generic-service-shutdown").log("Thread interrupted while shutting down service");
            return;
        }
        Exec e = runScript;
        if (e != null && e.isRunning()) {
            try {
                e.close();
                logger.atInfo().setEventType("generic-service-shutdown").log();
            } catch (IOException ioe) {
                logger.atError().setEventType("generic-service-shutdown-error").setCause(ioe).log();
            }
        }
        inShutdown = false;
    }

    @Override
    public void handleError() throws InterruptedException {
        // A placeholder for error handling in GenericExternalService
        run("handleError", null);
    }

    /**
     * Run one of the commands defined in the config on the command line.
     *
     * @param name       name of the command to run ("run", "install", "start").
     * @param background IntConsumer to receive the exit code. If null, the command will timeout after 2 minutes.
     * @return the status of the run.
     */
    protected RunStatus run(String name, IntConsumer background) throws InterruptedException {
        Node n = (getLifecycleTopic() == null) ? null : getLifecycleTopic().getChild(name);
        return n == null ? RunStatus.NothingDone : run(n, background);
    }

    protected RunStatus run(Node n, IntConsumer background) throws InterruptedException {
        return n instanceof Topic ? run((Topic) n, background, null)
                : n instanceof Topics ? run((Topics) n, background) : RunStatus.Errored;
    }

    protected RunStatus run(Topic t, IntConsumer background, Topics config) throws InterruptedException {
        return run(t, Coerce.toString(t.getOnce()), background, config);
    }

    // TODO: return Exec along with RunStatus instead of setting currentScript in this function
    @SuppressWarnings("PMD.CloseResource")
    protected RunStatus run(Topic t, String cmd, IntConsumer background, Topics config) throws InterruptedException {
        final ShellRunner shellRunner = context.get(ShellRunner.class);
        final EZTemplates templateEngine = context.get(EZTemplates.class);
        cmd = templateEngine.rewrite(cmd).toString();
        Exec exec = shellRunner.setup(t.getFullName(), cmd, this);
        if (exec == null) {
            return RunStatus.NothingDone;
        }
        currentScript = exec;
        addEnv(exec, t.parent);
        logger.atDebug().setEventType("generic-service-run").log();
        RunStatus ret = shellRunner.successful(exec, cmd, background) ? RunStatus.OK : RunStatus.Errored;
        if (background == null) {
            currentScript = null;
        }
        return ret;
    }

    protected RunStatus run(Topics t, IntConsumer background) throws InterruptedException {
        if (shouldSkip(t)) {
            logger.atDebug().setEventType("generic-service-skipped").addKeyValue("script", t.getFullName()).log();
            return RunStatus.OK;
        }

        Node script = t.getChild("script");
        if (script instanceof Topic) {
            return run((Topic) script, background, t);
        } else {
            logger.atError().setEventType("generic-service-invalid-config").addKeyValue("configNode", t.getFullName())
                    .log("Missing script");
            return RunStatus.Errored;
        }
    }

    boolean shouldSkip(Topics n) {
        Node skipif = n.getChild("skipif");
        if (skipif instanceof Topic) {
            Topic tp = (Topic) skipif;
            String expr = String.valueOf(tp.getOnce()).trim();
            boolean neg = false;
            if (expr.startsWith("!")) {
                expr = expr.substring(1).trim();
                neg = true;
            }
            expr = context.get(EZTemplates.class).rewrite(expr).toString();
            Matcher m = skipcmd.matcher(expr);
            if (m.matches()) {
                switch (m.group(1)) {
                    case "onpath":
                        return Exec.which(m.group(2)) != null ^ neg; // XOR ?!?!
                    case "exists":
                        return Files.exists(Paths.get(context.get(Kernel.class).deTilde(m.group(2)))) ^ neg;
                    default:
                        logger.atError().setEventType("generic-service-invalid-config")
                                .addKeyValue("operator", m.group(1)).log("Unknown operator in skipif");
                        serviceErrored();
                        return false;
                }
            }
            logger.atError().setEventType("generic-service-invalid-config").addKeyValue("command received", expr)
                    .addKeyValue("valid pattern", SKIP_COMMAND_REGEX)
                    .log("Invalid format for skipif. Should follow the pattern");
            serviceErrored();
        }
        return false;
    }

    private void addEnv(Exec exec, Topics src) {
        if (src == null) {
            return;
        }

        addEnv(exec, src.parent); // add parents contributions first
        Node env = src.getChild("setenv");
        if (env instanceof Topics) {
            EZTemplates templateEngine = context.get(EZTemplates.class);
            ((Topics) env).forEach(n -> {
                if (n instanceof Topic) {
                    exec.setenv(n.getName(), templateEngine.rewrite(Coerce.toString(((Topic) n).getOnce())));
                }
            });
        }
    }
}
