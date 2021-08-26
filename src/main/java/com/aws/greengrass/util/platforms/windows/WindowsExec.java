/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    private static final String PATHEXT_KEY = "PATHEXT";
    private static final String LOCAL_DOMAIN = ".";
    private static final List<String> PATHEXT;  // ordered file extensions to try, when no extension is provided
    public static final String SYSTEM_ROOT = "SystemRoot";

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
        String[] decorated = Arrays.copyOf(cmds, cmds.length);
        for (int i = 0; i < decorated.length; i++) {
            final String arg = decorated[i];
            // Space and \t require quoting otherwise will be split to more than one arg
            if (!isQuoted(arg) && arg.matches(".*[ \t].*")) {
                decorated[i] = "\"" + arg + "\"";
            }
        }
        if (shellDecorator != null) {
            decorated = shellDecorator.decorate(decorated);
        }
        return decorated;
    }

    @Override
    protected Process createProcess() throws IOException {
        if (needToSwitchUser()) {
            WindowsRunasProcess winProcess = new WindowsRunasProcess(LOCAL_DOMAIN, userDecorator.getUser());
            winProcess.setAdditionalEnv(environment);
            winProcess.setCurrentDirectory(dir.getAbsolutePath());
            winProcess.start(String.join(" ", getCommand()));
            return winProcess;
        } else {
            ProcessBuilder pb = new ProcessBuilder();
            pb.environment().putAll(environment);
            return pb.directory(dir).command(getCommand()).start();
        }
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

    /**
     * Returns true if we need to create process as another user. Otherwise, just use ProcessBuilder.
     */
    private boolean needToSwitchUser() throws IOException {
        if (userDecorator == null) {
            return false;
        }
        // check if same as current user
        UserPlatform.UserAttributes currUser = Platform.getInstance().lookupCurrentUser();
        return !(currUser.getPrincipalName().equals(userDecorator.getUser()) || currUser.getPrincipalIdentifier()
                .equals(userDecorator.getUser()));
    }

    private static boolean isAbsolutePath(String p) {
        return new File(p).isAbsolute();
    }

    private static boolean isQuoted(String s) {
        return s.startsWith("\"") && s.endsWith("\"") && !s.endsWith("\\\"");
    }
}
