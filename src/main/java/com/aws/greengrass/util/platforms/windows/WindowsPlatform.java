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
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class WindowsPlatform extends Platform {
    private static final String NAMED_PIPE = "\\\\.\\pipe\\NucleusNamedPipe-" + UUID.randomUUID().toString();

    // These sets of permissions are reverse engineered by setting the permission on a file using Windows File
    // Explorer. Then use AclFileAttributeView.getAcl to examine what were set.
    private static final Set<AclEntryPermission> READ_PERMS = new HashSet<>(Arrays.asList(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE));
    private static final Set<AclEntryPermission> WRITE_PERMS = new HashSet<>(Arrays.asList(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.SYNCHRONIZE));
    private static final Set<AclEntryPermission> EXECUTE_PERMS = new HashSet<>(Arrays.asList(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE));

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
        List<AclEntry> acl = aclEntries(permission, path);
        logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH, path).kv("perm", acl.toString()).log();
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        view.setAcl(acl); // This also clears existing acl!
    }

    /**
     * Convert to a list of Acl entries for use with AclFileAttributeView.setAcl.
     *
     * @param path path to apply to
     * @return List of Acl entries
     * @throws IOException if any exception occurs while converting to Acl
     */
    private List<AclEntry> aclEntries(FileSystemPermission permission, Path path) throws IOException {
        UserPrincipalLookupService userPrincipalLookupService = path.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal ownerPrincipal = userPrincipalLookupService.lookupPrincipalByName(permission.getOwnerUser());
        GroupPrincipal everyone = userPrincipalLookupService.lookupPrincipalByGroupName("Everyone");

        List<AclEntry> aclEntries = new ArrayList<>();
        if (permission.isOwnerRead()) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerPrincipal)
                    .setPermissions(READ_PERMS)
                    .build());
        }
        if (permission.isOwnerWrite()) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerPrincipal)
                    .setPermissions(WRITE_PERMS)
                    .build());
        }
        if (permission.isOwnerExecute()) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerPrincipal)
                    .setPermissions(EXECUTE_PERMS)
                    .build());
        }

        // There is no default group concept on Windows. (There is, but is used when mounting as a network share.)

        if (permission.isOtherRead()) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(everyone)
                    .setPermissions(READ_PERMS)
                    .build());
        }
        if (permission.isOtherWrite()) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(everyone)
                    .setPermissions(WRITE_PERMS)
                    .build());
        }
        if (permission.isOtherExecute()) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(everyone)
                    .setPermissions(EXECUTE_PERMS)
                    .build());
        }

        return aclEntries;
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
