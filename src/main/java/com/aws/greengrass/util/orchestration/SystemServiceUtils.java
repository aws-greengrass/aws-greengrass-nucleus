/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.orchestration;

import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;

public interface SystemServiceUtils {
    /**
     * Setup Greengrass as a system service.
     *
     * @param kernelAlternatives KernelAlternatives instance which manages launch directory
     * @param nucleusPaths NucleusPaths instance which manages Nucleus root paths
     * @param start Whether or not to start the service right away
     * @return true if setup is successful, false otherwise
     */
    boolean setupSystemService(KernelAlternatives kernelAlternatives, NucleusPaths nucleusPaths, boolean start);

    /**
     * Simply run a command with privileges.
     *
     * @param logger Logger to use
     * @param eventName logging event
     * @param command command to run
     * @param ignoreError ignore errors from this command
     * @throws IOException for command failure
     * @throws InterruptedException if interrupted while running
     */
    @SuppressWarnings("PMD.CloseResource")
    static void runCommand(Logger logger, String eventName, String command, boolean ignoreError)
            throws IOException, InterruptedException {
        logger.atDebug(eventName).log(command);
        Exec exec = Platform.getInstance().createNewProcessRunner().withShell(command);
        if (Platform.getInstance().getPrivilegedUser() != null) {
            exec.withUser(Platform.getInstance().getPrivilegedUser());
        }
        if (Platform.getInstance().getPrivilegedGroup() != null) {
            exec.withGroup(Platform.getInstance().getPrivilegedGroup());
        }
        boolean success = exec
                .withOut(s -> logger.atWarn(eventName).kv("command", command).kv("stdout", s.toString().trim()).log())
                .withErr(s -> logger.atError(eventName).kv("command", command).kv("stderr", s.toString().trim()).log())
                .successful(true);
        if (!success && !ignoreError) {
            throw new IOException(String.format("Command %s failed", command));
        }
    }
}
