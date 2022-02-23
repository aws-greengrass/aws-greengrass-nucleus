/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import android.os.SystemClock;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.lang.Process;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

// FIXME: android: to be implemented
@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class AndroidShellExec extends AndroidGenericExec {
    private static final Logger staticLogger = LogManager.getLogger(AndroidShellExec.class);
    private int pid;

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
        pid = -1;
        try {
            Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            pid = (int) f.getLong(process);
            f.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException cause) {
            logger.atError().setCause(cause).log("Failed to get process pid");
        }
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
    public synchronized void close() throws IOException {
        if (isClosed.get()) {
            return;
        }
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }

        AndroidPlatform platformInstance = (AndroidPlatform) Platform.getInstance();

        Set<Integer> pids = Collections.emptySet();
        try {
            pids = platformInstance.killProcessAndChildren(p, false, pids, userDecorator);
            // TODO: [P41214162] configurable timeout
            // Wait for it to die, but ignore the outcome and just forcefully kill it and all its
            // children anyway. This way, any misbehaving children or grandchildren will be killed
            // whether or not the parent behaved appropriately.
            Long startTime = SystemClock.elapsedRealtime();
            while (((SystemClock.elapsedRealtime() - startTime) < gracefulShutdownTimeout.toMillis())
                    && (pids.stream().anyMatch(ppid -> {
                        try {
                            return platformInstance.isProcessAlive(ppid);
                        } catch (IOException ignored) {
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return false;
                    }))) {
                Thread.sleep(10);
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
