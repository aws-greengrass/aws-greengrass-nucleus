/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

public final class SudoUtil {
    /**
     * Alternative shell commands to try if default (sh) is not enabled for sudo
     */
    private static final String[] FALLBACK_SHELL_COMMANDS = {
            "/usr/bin/logbash"
    };

    private SudoUtil() {

    }

    /**
     * Skip test if current user cannot sudo to default kernel shell.
     *
     * @param kernel a kernel to check.
     */
    public static void assumeCanSudoShell(Kernel kernel) {
        assumeTrue(canSudoShell(kernel), "cannot sudo to shell as current user");
    }

    /**
     * Check if current user can sudo to default kernel shell. Attempt to fallback to other shell commands if default
     * fails.
     *
     * @param kernel a kernel to check.
     */
    public static boolean canSudoShell(Kernel kernel) {
        DeviceConfiguration config = new DeviceConfiguration(kernel.getConfig(), kernel.getKernelCommandLine());
        String shell = Coerce.toString(config.getRunWithDefaultPosixShell().getOnce());

        if (canSudoShell(shell)) {
            return true;
        }
        for (String cmd : FALLBACK_SHELL_COMMANDS) {
            if (canSudoShell(cmd)) {
                config.getRunWithDefaultPosixShell().withValue(cmd);
                return true;
            }
        }
        return false;
    }

    /**
     * Check if current user can sudo to specified shell.
     *
     * @param shell a shell to check
     * @return true if the user can sudo to the shell.
     */
    public static boolean canSudoShell(String shell) {
        try {
            return Platform.getInstance()
                    .createNewProcessRunner()
                    .successful(true, "sudo -u nobody " + shell + " -c 'echo hello'");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
