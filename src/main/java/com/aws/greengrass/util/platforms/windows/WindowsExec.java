/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Utils;
import org.zeroturnaround.process.Processes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    public static final String PATHEXT_KEY = "PATHEXT";

    private static final List<String> PATHEXT;  // ordered file extensions to try, when no extension is provided

    static {
        String pathExt = System.getenv(PATHEXT_KEY);
        PATHEXT = Arrays.asList(pathExt.split(File.pathSeparator));
    }

    @Nullable
    @Override
    public Path which(String fn) {
        String ext = Utils.extension(fn);
        if (isAbsolutePath(fn)) {
            if (!ext.isEmpty()) {
                Path f = Paths.get(fn);
                return Files.isExecutable(f) ? f : null;
            }
            // No extension provided. Try PATHEXT in order
            for (String extCandidate : PATHEXT) {
                Path f = Paths.get(fn + extCandidate);
                if (Files.isExecutable(f)) {
                    return f;
                }
            }
            return null;
        }
        // Search in paths
        for (Path d : paths) {
            Path f = d.resolve(fn);
            if (!ext.isEmpty() && Files.isExecutable(f)) {
                return f;
            }
            // No extension provided. Try PATHEXT in order
            for (String extCandidate : PATHEXT) {
                f = d.resolve(fn + extCandidate);
                if (Files.isExecutable(f)) {
                    return f;
                }
            }
        }
        return null;
    }

    @Override
    public String[] getCommand() {
        String[] decorated = cmds;
        if (shellDecorator != null) {
            decorated = shellDecorator.decorate(decorated);
        }
        // First item in the command is the executable. If it's given as absolute path, add quotes around it
        // in case the path contains space which will break the whole command line
        // See security remarks:
        // https://docs.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-createprocesswithlogonw
        if (isAbsolutePath(decorated[0])) {
            decorated[0] = String.format("\"%s\"", decorated[0]);
        }
        return decorated;
    }

    @Override
    protected Process createProcess() throws IOException {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().putAll(environment);
        return pb.directory(dir).command(getCommand()).start();
    }

    @Override
    public synchronized void close() throws IOException {
        if (isClosed.get()) {
            return;
        }
        if (process == null || !process.isAlive()) {
            return;
        }
        Process killerProcess = new ProcessBuilder().command("taskkill", "/f", "/t", "/pid",
                Integer.toString(Processes.newPidProcess(process).getPid())).start();
        try {
            killerProcess.waitFor();
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            process.destroyForcibly();
        }
    }

    private static boolean isAbsolutePath(String p) {
        return new File(p).isAbsolute();
    }
}
