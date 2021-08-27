/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.ExecBase;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.sun.jna.platform.win32.Advapi32Util;

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
public class WindowsExec extends ExecBase {
    public static final String PATHEXT_KEY = "PATHEXT";

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
    protected Process createProcess() {
        WindowsRunasProcess winProcess = new WindowsRunasProcess(null, userDecorator.getUser());
        winProcess.setLpEnvironment(computeEnvironmentBlock());
        winProcess.setLpCurrentDirectory(dir.getAbsolutePath());
        winProcess.start(String.join(" ", getCommand()));
        return winProcess;
    }

    @Override
    public synchronized void close() throws IOException {
        if (isClosed.get()) {
            return;
        }
        if (process == null || !process.isAlive()) {
            return;
        }
        // TODO first try to shutdown process and children gracefully
        // Then force kill if not stopped within timeout
        Platform platformInstance = Platform.getInstance();
        try {
            platformInstance.killProcessAndChildren(process, false, Collections.emptySet(), userDecorator);
        } catch (InterruptedException e) {
            // If we're interrupted make sure to kill the process before returning
            try {
                platformInstance.killProcessAndChildren(process, true, Collections.emptySet(), userDecorator);
            } catch (InterruptedException ignore) {
            }
        }
    }

    private static boolean isAbsolutePath(String p) {
        return new File(p).isAbsolute();
    }

    /**
     * Convert environment Map to lpEnvironment block format.
     * @return environment block for starting a process
     */
    private String computeEnvironmentBlock() {
        // Add SystemRoot env var if exists. See comment:
        // https://github.com/openjdk/jdk/blob/b17b821/src/java.base/windows/classes/java/lang/ProcessEnvironment.java#L309-L311
        String systemRootVal = System.getenv(SYSTEM_ROOT);
        if (systemRootVal != null) {
            environment.put(SYSTEM_ROOT, systemRootVal);
        }
        return Advapi32Util.getEnvironmentBlock(environment);
    }
}
