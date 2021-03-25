/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.RunWithGenerator;
import com.aws.greengrass.util.platforms.ShellDecorator;
import com.aws.greengrass.util.platforms.UserDecorator;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import lombok.NoArgsConstructor;
import org.zeroturnaround.exec.InvalidExitValueException;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;
import org.zeroturnaround.process.WindowsProcess;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class WindowsPlatform extends Platform {
    private static final String NAMED_PIPE = "\\\\.\\pipe\\NucleusNamedPipe-" + UUID.randomUUID().toString();

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
    protected void setOwner(FileSystemPermission permission, Path path) throws IOException {
        UserPrincipalLookupService lookupService = path.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal userPrincipal = lookupService.lookupPrincipalByName(permission.getOwnerUser());
        FileOwnerAttributeView view = Files.getFileAttributeView(path, FileOwnerAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);

        logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH, path).kv("owner", permission.getOwnerUser())
                .log();
        view.setOwner(userPrincipal);

        // Note that group ownership is not used.
    }

    @Override
    protected void setMode(FileSystemPermission permission, Path path) throws IOException {
        List<AclEntry> acl = permission.toAclEntries(path);
        logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH, path).kv("perm", acl.toString()).log();
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        List<AclEntry> existingAcl = view.getAcl();
        existingAcl.addAll(0, acl); // insert before any DENY entries
        view.setAcl(existingAcl);
    }

    @Override
    public boolean userExists(String user) {
        try {
            Advapi32Util.getAccountByName(user);
            return true;
        } catch (Win32Exception e) {
            return false;
        }
    }

    @Override
    public BasicAttributes lookupGroupByName(String group) throws IOException {
        return null;
    }

    @Override
    public BasicAttributes lookupGroupByIdentifier(String identifier) throws IOException {
        return null;
    }

    @Override
    public WindowsUserAttributes lookupCurrentUser() throws IOException {
        String user = System.getProperty("user.name");
        if (Utils.isEmpty(user)) {
            throw new IOException("No user to lookup");
        }

        Advapi32Util.Account account;
        try {
            account = Advapi32Util.getAccountByName(user);
        } catch (Win32Exception e) {
            throw new IOException("Unrecognized user: " + user, e);
        }

        return WindowsUserAttributes.builder().principalName(account.name).principalIdentifier(account.sidString)
                .build();
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
    public String prepareIpcFilepath(Path rootPath) {
        return NAMED_PIPE;
    }

    @Override
    public String prepareIpcFilepathForComponent(Path rootPath) {
        return NAMED_PIPE;
    }

    @Override
    public String prepareIpcFilepathForRpcServer(Path rootPath) {
        return NAMED_PIPE;
    }

    @Override
    public void setIpcFilePermissions(Path rootPath) {
    }

    @Override
    public void cleanupIpcFiles(Path rootPath) {
    }
}
