/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.util.platforms;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
        // Use slay on QNX because kill doesn't exist, and we can't link to libc
        String[] cmd = {"slay", "-" + (force ? SIGKILL : SIGINT), "-f", "-Q", Integer.toString(pid)};
        Runtime.getRuntime().exec(cmd).waitFor();
    }
}
