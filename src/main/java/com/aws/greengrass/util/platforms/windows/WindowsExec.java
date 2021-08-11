/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.util.platforms.Exec;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;
import javax.annotation.Nullable;

@SuppressWarnings("PMD.AvoidCatchingThrowable")
public class WindowsExec extends Exec {
    @Nullable
    @Override
    public Path which(String fn) {
        // TODO
        return null;
    }

    @Override
    protected Process createProcess(String[] command) {
        WindowsPlatform.WindowsRunasUserOptions userOptions =
                (WindowsPlatform.WindowsRunasUserOptions) this.userOptions;
        WindowsRunasProcess process = new WindowsRunasProcess(userOptions.getDomain(), userOptions.getUser(),
                userOptions.getPassword());
        process.setLpEnvironment(computeEnvironmentBlock());
        process.setLpCurrentDirectory(dir.getAbsolutePath());
        process.start(String.join(" ", command));
        return process;
    }

    /**
     * Convert environment Map to lpEnvironment block format.
     * @return environment block for starting a process
     */
    private String computeEnvironmentBlock() {
        // TODO: is reflection good here or should we copy over the source code
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
            staticLogger.atError().setCause(t)
                    .log("Failed to compute environment block. Creating process without env modifications");
        }
        return block;
    }
}
