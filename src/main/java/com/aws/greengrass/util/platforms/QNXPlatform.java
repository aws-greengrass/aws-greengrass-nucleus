/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.util.Utils.inputStreamToString;

public class QNXPlatform extends UnixPlatform {
    @Override
    public void killProcessAndChildren(Process process, boolean force) throws IOException, InterruptedException {
        List<Integer> childPids = getChildPids(process);
        for (Integer childPid : childPids) {
            killUsingSlay(childPid, force);
        }

        // If forcible, then also kill the parent (the shell)
        if (force) {
            process.destroy();
            process.waitFor(2, TimeUnit.SECONDS);
            process.destroyForcibly();
        }
    }

    private void killUsingSlay(int pid, boolean force) throws IOException, InterruptedException {
        logger.atDebug().log("Running slay to kill pid {}", pid);
        // Use slay on QNX because kill doesn't exist, and we can't link to libc
        String[] cmd = {"slay", "-" + (force ? SIGKILL : SIGINT), "-f", "-Q", Integer.toString(pid)};
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
