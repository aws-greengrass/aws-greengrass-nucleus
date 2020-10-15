/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.util.FileSystemPermission;
import lombok.NoArgsConstructor;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;
import org.zeroturnaround.process.WindowsProcess;

import java.io.IOException;
import java.nio.file.Path;

public class WindowsPlatform extends Platform {
    @Override
    public void killProcessAndChildren(Process process, boolean force) throws IOException, InterruptedException {
        PidProcess pp = Processes.newPidProcess(process);
        ((WindowsProcess) pp).setIncludeChildren(true);
        ((WindowsProcess) pp).setGracefulDestroyEnabled(true);

        try {
            pp.destroy(force);
        } catch (InvalidExitValueException e) {
            // zeroturnaround executes `taskkill` to kill a process. Sometimes taskkill's exit code is not 0, signalling
            // an error. One of reason is that the process is not there anymore. In such case, if the process is not
            // alive anymore, we can just ignore the exception.
            //
            // In other words, we rethrow the exception if the process is still alive.
            if (process.isAlive()) {
                throw e;
            }
        }
    }

    @Override
    public CommandDecorator getShellDecorator() {
        return new CmdDecorator();
    }

    @Override
    public int exitCodeWhenCommandDoesNotExist() {
        return 1;
    }

    @Override
    public UserDecorator getUserDecorator() {
        throw new UnsupportedOperationException("cannot run as another user");
    }

    @NoArgsConstructor
    public static class CmdDecorator implements CommandDecorator {
        @Override
        public String[] decorate(String... command) {
            String[] ret = new String[command.length + 2];
            ret[0] = "cmd.exe";
            ret[1] = "/C";
            System.arraycopy(command, 0, ret, 2, command.length);
            return ret;
        }
    }
  
    @Override
    public void setPermissions(FileSystemPermission permission, Path path) throws IOException {
        // TODO: Implement using ACL for Windows
    }
}
