/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.platforms.Platform;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class UnixExec extends Exec {
    private int pid;

    static {
        // Ensure some level of sanity in the PATH
        ensurePresent("/bin", "/usr/bin", "/sbin", "/usr/sbin");
        computeDefaultPathString();
    }

    private final Lock lock = LockFactory.newReentrantLock(this);

    UnixExec() {
        super();
        environment = new HashMap<>(defaultEnvironment);
    }

    private static void ensurePresent(String... fns) {
        for (String fn : fns) {
            Path ulb = Paths.get(fn);
            if (Files.isDirectory(ulb) && !paths.contains(ulb)) {
                paths.add(ulb);
            }
        }
    }

    @Nullable
    @Override
    public Path which(String fn) {
        fn = deTilde(fn);
        if (fn.startsWith("/")) {
            Path f = Paths.get(fn);
            return Files.isExecutable(f) ? f : null;
        }
        for (Path d : paths) {
            Path f = d.resolve(fn);
            if (Files.isExecutable(f)) {
                return f;
            }
        }
        return null;
    }

    @Override
    protected Process createProcess() throws IOException {
        String[] command = getCommand();
        logger.atTrace().kv("decorated command", String.join(" ", command)).log();
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().putAll(environment);
        process = pb.directory(dir).command(command).start();
        pid = Processes.newPidProcess(process).getPid();
        return process;
    }

    @Override
    public int getPid() {
        return pid;
    }

    @Override
    public String[] getCommand() {
        String[] decorated = cmds;
        if (shellDecorator != null) {
            decorated = shellDecorator.decorate(decorated);
        }
        if (userDecorator != null) {
            decorated = userDecorator.decorate(decorated);
        }
        return decorated;
    }

    @Override
    public void close() throws IOException {
        try (LockScope ls = LockScope.lock(lock)) {
            if (isClosed.get()) {
                return;
            }
            Process p = process;
            if (p == null || !p.isAlive()) {
                return;
            }

            Platform platformInstance = Platform.getInstance();

            Set<Integer> pids = Collections.emptySet();
            try {
                pids = platformInstance.killProcessAndChildren(p, false, pids, userDecorator);
                // TODO: [P41214162] configurable timeout
                // Wait for it to die, but ignore the outcome and just forcefully kill it and all its
                // children anyway. This way, any misbehaving children or grandchildren will be killed
                // whether or not the parent behaved appropriately.

                // Wait up to 5 seconds for each child process to stop
                List<PidProcess> pidProcesses =
                        pids.stream().map(Processes::newPidProcess).collect(Collectors.toList());
                for (PidProcess pp : pidProcesses) {
                    pp.waitFor(gracefulShutdownTimeout.getSeconds(), TimeUnit.SECONDS);
                }
                if (pidProcesses.stream().anyMatch(pidProcess -> {
                    try {
                        return pidProcess.isAlive();
                    } catch (IOException ignored) {
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                })) {
                    logger.atWarn()
                            .log("Command {} did not respond to interruption within timeout. Going to kill it now",
                                    this);
                }
                platformInstance.killProcessAndChildren(p, true, pids, userDecorator);
                if (!p.waitFor(5, TimeUnit.SECONDS) && !isClosed.get()) {
                    throw new IOException("Could not stop " + this);
                }
            } catch (InterruptedException e) {
                // If we're interrupted make sure to kill the process before returning
                try {
                    platformInstance.killProcessAndChildren(p, true, pids, userDecorator);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }
}
