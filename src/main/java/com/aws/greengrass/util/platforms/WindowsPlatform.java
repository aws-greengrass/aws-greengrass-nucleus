/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.util.FileSystemPermission;
import lombok.NoArgsConstructor;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;
import org.zeroturnaround.process.WindowsProcess;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class WindowsPlatform extends Platform {
    private static final String NAMED_PIPE = "\\\\.\\pipe\\NucleusNamedPipe";

    @Override
    public Set<Integer> killProcessAndChildren(Process process, boolean force, Set<Integer> additionalPids,
                                               UserDecorator decorator)
            throws IOException, InterruptedException {
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
        return Collections.emptySet();
    }

    @Override
    public ShellDecorator getShellDecorator() {
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

    @Override
    public String getPrivilegedGroup() {
        return null;
    }

    @Override
    public String getPrivilegedUser() {
        return null;
    }

    @Override
    public RunWithGenerator getRunWithGenerator() {
        return new RunWithGenerator() {
            @Override
            public void validateDefaultConfiguration(DeviceConfiguration deviceConfig)
                    throws DeviceConfigurationException {
                // do nothing
            }

            @Override
            public void validateDefaultConfiguration(Map<String, Object> proposedDeviceConfig)
                    throws DeviceConfigurationException {
                // do nothing
            }

            @Override
            public Optional<RunWith> generate(DeviceConfiguration deviceConfig, Topics config) {
                return Optional.of(RunWith.builder().user(System.getProperty("user.name")).build());
            }
        };
    }

    @Override
    public void createUser(String user) throws IOException {
        // TODO: [P41452086]: Windows support - create user/group, add user to group
    }

    @Override
    public void createGroup(String group) throws IOException {
        // TODO: [P41452086]: Windows support - create user/group, add user to group
    }

    @Override
    public void addUserToGroup(String user, String group) throws IOException {
        // TODO: [P41452086]: Windows support - create user/group, add user to group
    }

    @Override
    protected void setPermissions(FileSystemPermission permission, Path path,
                                  EnumSet<FileSystemPermission.Option> options) throws IOException {
        // [P41372857]: Implement using ACL for Windows
    }

    @Override
    public UserAttributes lookupUserByName(String user) throws IOException {
        return null;
    }

    @Override
    public UserAttributes lookupUserByIdentifier(String identifier) throws IOException {
        return null;
    }

    @Override
    public BasicAttributes lookupGroupByName(String group) throws IOException {
        return null;
    }

    @Override
    public BasicAttributes lookupGroupByIdentifier(String identifier) throws IOException {
        return null;
    }

    @NoArgsConstructor
    public static class CmdDecorator implements ShellDecorator {

        @Override
        public String[] decorate(String... command) {
            String[] ret = new String[command.length + 2];
            ret[0] = "cmd.exe";
            ret[1] = "/C";
            System.arraycopy(command, 0, ret, 2, command.length);
            return ret;
        }

        @Override
        public ShellDecorator withShell(String shell) {
            throw new UnsupportedOperationException("changing shell is not supported");
        }
    }

    @Override
    public String prepareDomainSocketFilepath() {
        return NAMED_PIPE;
    }

    @Override
    public String prepareDomainSocketFilepathForComponent() {
        return NAMED_PIPE;
    }

    @Override
    public String prepareDomainSocketFilepathForRpcServer() {
        return NAMED_PIPE;
    }

    @Override
    public void setIpcBackingFilePermissions() {
    }

    @Override
    public void cleanupIpcBackingFile() {
    }
}
