/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.RunWithGenerator;
import com.aws.greengrass.util.platforms.ShellDecorator;
import com.aws.greengrass.util.platforms.StubResourceController;
import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.UserDecorator;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinBase;
import lombok.Getter;
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
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.WINDOWS_USER_KEY;
import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_KERNEL_OBJECT;
import static com.sun.jna.platform.win32.WinNT.DACL_SECURITY_INFORMATION;
import static com.sun.jna.platform.win32.WinNT.FILE_ATTRIBUTE_NORMAL;
import static com.sun.jna.platform.win32.WinNT.FILE_SHARE_READ;
import static com.sun.jna.platform.win32.WinNT.GENERIC_ALL;
import static com.sun.jna.platform.win32.WinNT.HANDLE;
import static com.sun.jna.platform.win32.WinNT.OPEN_EXISTING;
import static com.sun.jna.platform.win32.WinNT.WRITE_OWNER;

public class WindowsPlatform extends Platform {
    private static final String NAMED_PIPE_PREFIX = "\\\\.\\pipe\\NucleusNamedPipe-";
    private static final String NAMED_PIPE_UUID_SUFFIX = UUID.randomUUID().toString();
    private static final int MAX_NAMED_PIPE_LEN = 256;
    protected static final String LOCAL_SYSTEM_SID = "S-1-5-18";
    protected static final String ADMINISTRATORS_SID = "S-1-5-32-544";
    protected static final String LOCAL_SYSTEM_USERNAME = "SYSTEM";
    protected static final WindowsUserAttributes LOCAL_SYSTEM_USER_ATTRIBUTES =
            WindowsUserAttributes.builder().superUser(true).superUserKnown(true)
                    .principalIdentifier(LOCAL_SYSTEM_SID).principalName(LOCAL_SYSTEM_USERNAME)
                    .build();

    private final SystemResourceController systemResourceController = new StubResourceController();
    private static WindowsUserAttributes CURRENT_USER;

    static final Set<AclEntryPermission> READ_PERMS = new HashSet<>(Arrays.asList(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE));
    static final Set<AclEntryPermission> WRITE_PERMS = new HashSet<>(Arrays.asList(
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.DELETE,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
            AclEntryPermission.SYNCHRONIZE));
    static final Set<AclEntryPermission> EXECUTE_PERMS = new HashSet<>(Arrays.asList(
            AclEntryPermission.READ_NAMED_ATTRS,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.READ_ATTRIBUTES,
            AclEntryPermission.READ_ACL,
            AclEntryPermission.SYNCHRONIZE));
    static final Set<AclEntryPermission> ALL_PERMS = new HashSet<>();

    static {
        ALL_PERMS.addAll(READ_PERMS);
        ALL_PERMS.addAll(WRITE_PERMS);
        ALL_PERMS.addAll(EXECUTE_PERMS);
    }

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
        return new RunasDecorator();
    }

    @Override
    public String getPrivilegedGroup() {
        return "Administrators";
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
                // TODO
            }

            @Override
            public void validateDefaultConfiguration(Map<String, Object> proposedDeviceConfig)
                    throws DeviceConfigurationException {
                // TODO check user actually exists and we have the credential
            }

            @Override
            public Optional<RunWith> generate(DeviceConfiguration deviceConfig, Topics config) {
                // check component runWith, then runWithDefault
                String user = Coerce.toString(config.find(RUN_WITH_NAMESPACE_TOPIC, WINDOWS_USER_KEY));
                boolean isDefault = false;

                if (Utils.isEmpty(user)) {
                    logger.atDebug().setEventType("generate-runwith").log("No component user, check default");
                    user = Coerce.toString(deviceConfig.getRunWithDefaultWindowsUser());
                    isDefault = true;
                }

                if (Utils.isEmpty(user)) {
                    logger.atDebug().setEventType("generate-runwith").log("No default user");
                    return Optional.empty();
                } else {
                    return Optional.of(RunWith.builder().user(user).isDefault(isDefault).build());
                }
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
    public SystemResourceController getSystemResourceController() {
        return systemResourceController;
    }

    @Override
    public Exec createNewProcessRunner() {
        return new WindowsExec();
    }

    @Override
    protected void setOwner(UserPrincipal userPrincipal, GroupPrincipal groupPrincipal, Path path) throws IOException {
        AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);

        if (userPrincipal != null && !userPrincipal.equals(view.getOwner())) {
            logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH_LOG_KEY, path).kv("owner", userPrincipal.toString())
                    .log();

            // Changing ownership on Windows does not automatically grant any rights to the new owner.
            // To make this behave like Unix we will actually move the permissions from the old owner over to
            // the new owner.

            UserPrincipal existingOwner = view.getOwner();
            List<AclEntry> currentAcl = view.getAcl();
            Set<AclEntry> newAcl = currentAcl.stream()
                    .filter((a) -> !existingOwner.equals(a.principal()))
                    .collect(Collectors.toSet());

            Stream<AclEntry> s = currentAcl.stream()
                    .filter((a) -> existingOwner.equals(a.principal())); // Find all rights of the current owner

            GroupPrincipal greengrassPrincipal = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName(getPrivilegedGroup());
            // If the current owner is the Administrator's group, then we need to do a bit of filtering
            // so that Greengrass retains all the permissions we require.
            if (existingOwner.equals(greengrassPrincipal)) {
                AtomicBoolean removedOne = new AtomicBoolean(false);
                s = s.filter(a -> {
                    // Only operate on one ACL with the given principal and permissions.
                    // If we have explicitly given the owner ALL_PERMS then there will be 2 ACLs
                    // which match, so we will remap one of these and keep the other one with the Greengrass
                    // principal.
                    if (!removedOne.get() && a.principal().equals(existingOwner) && a.permissions().equals(ALL_PERMS)) {
                        removedOne.set(true);
                        // Add the permission into the new acl list so that Greengrass retains our permissions
                        newAcl.add(a);
                        // Filter out the permission so that we do not remap this permission over to the new owner
                        return false;
                    }
                    return true;
                });
            }

            // Copy over the permissions while changing the principal
            newAcl.addAll(s.map(a -> AclEntry.newBuilder()
                    .setFlags(a.flags())
                    .setPermissions(a.permissions())
                    .setType(a.type())
                    .setPrincipal(userPrincipal)
                    .build())
                    .collect(Collectors.toSet()));
            view.setAcl(new ArrayList<>(newAcl));
            view.setOwner(userPrincipal);
        }

        // Note that group ownership is not used.
    }

    @Override
    public UserPrincipal lookupUserByName(Path path, String name) throws IOException {
        // When running as the SYSTEM user, the name from the "user.name" property is "<hostname>$".
        // This name cannot be looked up normally, but it is well known, so we can account for it easily.
        // https://docs.microsoft.com/en-us/troubleshoot/windows-server/identity/compatibility-user-accounts-end-dollar-sign
        if (name.endsWith("$")) {
            name = LOCAL_SYSTEM_USERNAME;
        }
        return path.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(name);
    }

    @Getter
    public static class WindowsFileSystemPermissionView extends FileSystemPermissionView {

        private List<AclEntry> acl;

        public WindowsFileSystemPermissionView(FileSystemPermission permission, Path path) throws IOException {
            super();
            acl = aclEntries(permission, path);
        }

        /**
         * Convert to a list of Acl entries for use with AclFileAttributeView.setAcl.
         *
         * @param permission permission to convert
         * @param path path to apply to
         * @return List of Acl entries
         * @throws IOException if any exception occurs while converting to Acl
         */
        public static List<AclEntry> aclEntries(FileSystemPermission permission, Path path) throws IOException {
            UserPrincipalLookupService userPrincipalLookupService =
                    path.getFileSystem().getUserPrincipalLookupService();

            // Owner
            UserPrincipal ownerPrincipal;
            if (Utils.isEmpty(permission.getOwnerUser())) {
                // On Linux, when we set the file permission for the owner, it applies to the current owner and we don't
                // need to know who the actual owner is. But on Windows, Acl must be associated with an owner.
                AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                        LinkOption.NOFOLLOW_LINKS);
                ownerPrincipal = view.getOwner();
            } else {
                ownerPrincipal = WindowsPlatform.getInstance().lookupUserByName(path, permission.getOwnerUser());
            }

            Set<AclEntryFlag> flags = new HashSet<>();
            flags.add(AclEntryFlag.DIRECTORY_INHERIT);
            flags.add(AclEntryFlag.FILE_INHERIT);
            List<AclEntry> aclEntries = new ArrayList<>();
            if (permission.isOwnerRead()) {
                aclEntries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(ownerPrincipal)
                        .setPermissions(READ_PERMS)
                        .setFlags(flags)
                        .build());
            }
            if (permission.isOwnerWrite()) {
                aclEntries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(ownerPrincipal)
                        .setPermissions(WRITE_PERMS)
                        .setFlags(flags)
                        .build());
            }
            if (permission.isOwnerExecute()) {
                aclEntries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(ownerPrincipal)
                        .setPermissions(EXECUTE_PERMS)
                        .setFlags(flags)
                        .build());
            }

            // Group
            if (!Utils.isEmpty(permission.getOwnerGroup())) {
                GroupPrincipal groupPrincipal =
                        userPrincipalLookupService.lookupPrincipalByGroupName(permission.getOwnerGroup());
                if (permission.isGroupRead()) {
                    aclEntries.add(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(groupPrincipal)
                            .setPermissions(READ_PERMS)
                            .setFlags(flags)
                            .build());
                }
                if (permission.isGroupWrite()) {
                    aclEntries.add(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(groupPrincipal)
                            .setPermissions(WRITE_PERMS)
                            .setFlags(flags)
                            .build());
                }
                if (permission.isGroupExecute()) {
                    aclEntries.add(AclEntry.newBuilder()
                            .setType(AclEntryType.ALLOW)
                            .setPrincipal(groupPrincipal)
                            .setPermissions(EXECUTE_PERMS)
                            .setFlags(flags)
                            .build());
                }
            }

            // Other
            GroupPrincipal everyone = userPrincipalLookupService.lookupPrincipalByGroupName("Everyone");
            if (permission.isOtherRead()) {
                aclEntries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(everyone)
                        .setPermissions(READ_PERMS)
                        .setFlags(flags)
                        .build());
            }
            if (permission.isOtherWrite()) {
                aclEntries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(everyone)
                        .setPermissions(WRITE_PERMS)
                        .setFlags(flags)
                        .build());
            }
            if (permission.isOtherExecute()) {
                aclEntries.add(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(everyone)
                        .setPermissions(EXECUTE_PERMS)
                        .setFlags(flags)
                        .build());
            }

            // We automatically add permissions for Administrators group since Greengrass will always run as
            // someone in the Administrators group. This ensures that Greengrass will have the requisite permissions
            // to change ownership and permissions as needed.
            GroupPrincipal systemPrincipal = path.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByGroupName(WindowsPlatform.getInstance().getPrivilegedGroup());
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(systemPrincipal)
                    .setPermissions(ALL_PERMS)
                    .setFlags(flags)
                    .build());

            return aclEntries;
        }
    }

    @Override
    protected FileSystemPermissionView getFileSystemPermissionView(FileSystemPermission permission, Path path)
            throws IOException {
        return new WindowsFileSystemPermissionView(permission, path);
    }

    @Override
    protected void setMode(FileSystemPermissionView permissionView, Path path) throws IOException {
        if (permissionView instanceof WindowsFileSystemPermissionView) {
            WindowsFileSystemPermissionView windowsFileSystemPermissionView =
                    (WindowsFileSystemPermissionView) permissionView;
            List<AclEntry> acl = windowsFileSystemPermissionView.getAcl();

            AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);

            List<AclEntry> currentAcl = view.getAcl();
            if (!currentAcl.equals(acl)) {
                logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH_LOG_KEY, path).kv("perm", acl.toString()).log();
                view.setAcl(acl); // This also clears existing acl!
            }
        }
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
    public WindowsGroupAttributes lookupGroupByName(String group) {
        Advapi32Util.Account account = Advapi32Util.getAccountByName(group);
        return WindowsGroupAttributes.builder().principalName(account.name).principalIdentifier(account.sidString)
                .build();
    }

    @Override
    public WindowsGroupAttributes lookupGroupByIdentifier(String identifier) {
        Advapi32Util.Account account = Advapi32Util.getAccountBySid(identifier);
        return WindowsGroupAttributes.builder().principalName(account.name).principalIdentifier(account.sidString)
                .build();
    }

    @Override
    public WindowsUserAttributes lookupCurrentUser() throws IOException {
        return loadCurrentUser();
    }

    private static synchronized WindowsUserAttributes loadCurrentUser() throws IOException {
        if (CURRENT_USER != null) {
            return CURRENT_USER;
        }

        String user = System.getProperty("user.name");
        if (Utils.isEmpty(user)) {
            throw new IOException("No user to lookup");
        }

        // Looking up "SYSTEM" will always fail, so short circuit with its well known attributes
        if (user.endsWith("$") || user.equals(LOCAL_SYSTEM_USERNAME)) {
            CURRENT_USER = LOCAL_SYSTEM_USER_ATTRIBUTES;
            return CURRENT_USER;
        }

        Advapi32Util.Account account;
        try {
            account = Advapi32Util.getAccountByName(user);
        } catch (Win32Exception e) {
            throw new IOException("Unrecognized user: " + user, e);
        }
        boolean superUser = false;
        for (Advapi32Util.Account group : Advapi32Util.getCurrentUserGroups()) {
            if (ADMINISTRATORS_SID.equalsIgnoreCase(group.sidString)) {
                superUser = true;
                break;
            }
        }

        CURRENT_USER = WindowsUserAttributes.builder()
                .principalName(account.name)
                .principalIdentifier(account.sidString)
                .superUserKnown(true)
                .superUser(superUser)
                .build();
        return CURRENT_USER;
    }

    /**
     * Defaults to cmd, allowed to set to powershell.
     */
    public static class CmdDecorator implements ShellDecorator {
        private static final String CMD = "cmd";
        private static final String CMD_ARG = "/C";
        private static final String POWERSHELL = "powershell";
        private static final String POWERSHELL_ARG = "-Command";
        private String shell = CMD;
        private String arg = CMD_ARG;

        @Override
        public String[] decorate(String... command) {
            String[] ret = new String[command.length + 2];
            ret[0] = shell;
            ret[1] = arg;
            System.arraycopy(command, 0, ret, 2, command.length);
            return ret;
        }

        @Override
        public ShellDecorator withShell(String shell) {
            if (Utils.isEmpty(shell)) {
                this.shell = CMD;
                this.arg = CMD_ARG;
                return this;
            }
            switch (shell) {
                case CMD:
                    this.shell = CMD;
                    this.arg = CMD_ARG;
                    break;
                case POWERSHELL:
                    this.shell = POWERSHELL;
                    this.arg = POWERSHELL_ARG;
                    break;
                default:
                    throw new UnsupportedOperationException("Invalid Windows shell: " + shell);
            }
            return this;
        }
    }

    @Override
    public String prepareIpcFilepath(Path rootPath) {
        String absolutePath = rootPath.toAbsolutePath().toString().replaceAll("[^a-zA-Z0-9-]", "");
        if (NAMED_PIPE_PREFIX.length() + absolutePath.length() <= MAX_NAMED_PIPE_LEN) {
            return NAMED_PIPE_PREFIX + absolutePath;
        }

        return NAMED_PIPE_PREFIX + NAMED_PIPE_UUID_SUFFIX;
    }

    @Override
    public String prepareIpcFilepathForComponent(Path rootPath) {
        return prepareIpcFilepath(rootPath);
    }

    @Override
    public String prepareIpcFilepathForRpcServer(Path rootPath) {
        return prepareIpcFilepath(rootPath);
    }

    @Override
    public void setIpcFilePermissions(Path rootPath) {
        String namedPipe = prepareIpcFilepathForRpcServer(rootPath);
        // Open up the named pipe using CreateFile to give us a Win32 handle
        HANDLE handle = Kernel32.INSTANCE.CreateFile(namedPipe,
                GENERIC_ALL | WRITE_OWNER,
                FILE_SHARE_READ,
                new WinBase.SECURITY_ATTRIBUTES(),
                OPEN_EXISTING,
                FILE_ATTRIBUTE_NORMAL,
                null);
        if (WinBase.INVALID_HANDLE_VALUE == handle) {
            throw new RuntimeException("Got invalid handle for named pipe " + namedPipe);
        }

        int ret = Advapi32.INSTANCE.SetSecurityInfo(handle,
                SE_KERNEL_OBJECT, DACL_SECURITY_INFORMATION, null, null,
                // https://docs.microsoft.com/en-us/windows/win32/secauthz/access-control-lists
                // "If the object does not have a DACL, the system grants full access to everyone."
                null,
                null);
        if (ret != 0) {
            throw new RuntimeException(
                    String.format("Unable to set ACL on named pipe, %s. Error code %d, possible message %s",
                            namedPipe, ret, Kernel32Util.formatMessageFromLastErrorCode(ret)));
        }
    }

    @Override
    public void cleanupIpcFiles(Path rootPath) {
    }

    @Override
    public String loaderFilename() {
        return "loader.cmd";
    }

    /**
     * Decorator for running a command as a different user with `runas`.
     */
    @NoArgsConstructor
    public static class RunasDecorator extends UserDecorator {
        @Override
        public String[] decorate(String... command) {
            // Windows decorate does nothing
            return command;
        }
    }
}
