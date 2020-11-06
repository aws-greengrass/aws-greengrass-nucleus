/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.util.platforms.UserDecorator;
import org.zeroturnaround.process.Processes;

import java.io.IOException;
import java.util.Set;

import static com.aws.greengrass.util.Utils.inputStreamToString;

public class QNXPlatform extends UnixPlatform {
    @Override
    public Set<Integer> killProcessAndChildren(Process process, boolean force, Set<Integer> additionalPids,
                                               UserDecorator decorator)
            throws IOException, InterruptedException {
        Set<Integer> childPids = getChildPids(process);
        for (Integer childPid : childPids) {
            if (Processes.newPidProcess(childPid).isAlive()) {
                continue;
            }
            killUsingSlay(childPid, force, decorator);
        }

        if (force) {
            process.destroyForcibly();
        } else {
            process.destroy();
        }

        return childPids;
    }

    private void killUsingSlay(int pid, boolean force, UserDecorator userDecorator)
            throws IOException, InterruptedException {
        logger.atInfo().log("Slaying pid {} with signal {}", pid, force ? SIGKILL : SIGINT);
        // Use slay on QNX because kill doesn't exist, and we can't link to libc
        String[] cmd = {"slay", "-" + (force ? SIGKILL : SIGINT), "-f", "-Q", Integer.toString(pid)};
        if (userDecorator != null) {
            cmd = userDecorator.decorate(cmd);
        }
        Process proc = Runtime.getRuntime().exec(cmd);
        proc.waitFor();
        // For slay, exit 0 is an error (https://www.qnx.com/developers/docs/6.3.0SP3/neutrino/utilities/s/slay.html)
        if (proc.exitValue() == 0) {
            logger.atWarn().kv("pid", pid)
                    .kv("stdout", inputStreamToString(proc.getInputStream()))
                    .kv("stderr", inputStreamToString(proc.getErrorStream()))
                    .log("slay exited with an error");
        }
    }
}
