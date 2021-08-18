/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Exec;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    public static final String PATHEXT_KEY = "PATHEXT";

    private static final Logger logger = LogManager.getLogger(WindowsExec.class);
    private static final List<String> PATHEXT;  // ordered file extensions to try, when no extension is provided

    static {
        String pathExt = System.getenv(PATHEXT_KEY);
        PATHEXT = Arrays.asList(pathExt.split(File.pathSeparator));
    }

    @Nullable
    @Override
    public Path which(String fn) {
        String ext = Utils.extension(fn);
        // If absolute path, first character expected to be the drive letter
        if (fn.substring(1).startsWith(":\\")) {
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
    protected Process createProcess() {
        WindowsRunasProcess winProcess = new WindowsRunasProcess(null, userDecorator.getUser());
        winProcess.setLpEnvironment(computeEnvironmentBlock());
        winProcess.setLpCurrentDirectory(dir.getAbsolutePath());
        winProcess.start(String.join(" ", cmds));
        return winProcess;
    }

    /**
     * Convert environment Map to lpEnvironment block format.
     * @return environment block for starting a process
     */
    private String computeEnvironmentBlock() {
        // TODO: copy over the source code so that it's the same regardless of Java version this is running on
        // http://hg.openjdk.java.net/jdk8/jdk8/jdk/file/687fd7c7986d/src/windows/classes/java/lang/ProcessEnvironment.java
        String block = null;
        try {
            Class<?> cl = Class.forName("java.lang.ProcessEnvironment");
            for (final Method method : cl.getDeclaredMethods()) {
                boolean correctMethod =
                        "toEnvironmentBlock".equals(method.getName()) && method.getParameterTypes().length == 1
                                && method.getParameterTypes()[0].equals(Map.class);
                if (correctMethod) {
                    method.setAccessible(true);
                    block = (String) method.invoke(null, environment);
                    break;
                }
            }
        } catch (Throwable t) {
            logger.atError().setCause(t)
                    .log("Failed to compute environment block. Creating process without env modifications");
        }
        return block;
    }
}
