/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.lang.Process;

import javax.annotation.Nullable;

// FIXME: android: to be implemented
@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class AndroidExec extends Exec {
    private static final Logger staticLogger = LogManager.getLogger(AndroidExec.class);
    private int pid;

    AndroidExec() {
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
        pid = -1;
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
        logger.atWarn().log("Close");
    }
}
