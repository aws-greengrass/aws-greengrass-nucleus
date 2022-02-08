/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.WINDOWS_USER_KEY;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.Permissions;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.RunWithGenerator;
import com.aws.greengrass.util.platforms.ShellDecorator;
import com.aws.greengrass.util.platforms.StubResourceController;
import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.UserDecorator;
import com.aws.greengrass.util.platforms.unix.UnixGroupAttributes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import lombok.Getter;
import lombok.NoArgsConstructor;

// FIXME: android: to be implemented
/**
 * Android specific platform implementation.
 */
public class AndroidPlatform extends Platform {

    public static final String PRIVILEGED_USER = "root";

    public static final String IPC_SERVER_DOMAIN_SOCKET_FILENAME = "ipc.socket";
    public static final String IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK = "./nucleusRoot/ipc.socket";
    public static final String NUCLEUS_ROOT_PATH_SYMLINK = "./nucleusRoot";
    // This is relative to component's CWD
    // components CWD is <kernel-root-path>/work/component
    public static final String IPC_SERVER_DOMAIN_SOCKET_RELATIVE_FILENAME = "../../ipc.socket";

    // https://www.gnu.org/software/libc/manual/html_node/Local-Namespace-Details.html
    private static final int UDS_SOCKET_PATH_MAX_LEN = 108;

    private static final int MAX_IPC_SOCKET_CREATION_WAIT_TIME_SECONDS = 30;
    public static final int SOCKET_CREATE_POLL_INTERVAL_MS = 200;

    private static AndroidUserAttributes CURRENT_USER;
    private static UnixGroupAttributes CURRENT_USER_PRIMARY_GROUP;

    private final SystemResourceController systemResourceController = new StubResourceController();

    private AndroidAppLevelAPI androidAppLevelAPI;
    private AndroidServiceLevelAPI androidServiceLevelAPI;

    /**
     * Set reference to Android Application Level interface to future references.
     */
    public void setAndroidAppLevelAPI(final AndroidAppLevelAPI androidAppLevelAPI) {
        this.androidAppLevelAPI = androidAppLevelAPI;
    }

    /**
     * Set reference to Android Service Level interface to future references.
     */
    public void setAndroidServiceLevelAPI(final AndroidServiceLevelAPI androidServiceLevelAPI) {
        this.androidServiceLevelAPI = androidServiceLevelAPI;
    }

    /**
     * Run the `id` program which returns user and group information about a particular user.
     *
     * @param id       the identifier (numeric or name). If empty, then the current user is looked up.
     * @param option   whether to load group or user information.
     * @param loadName whether a name should or id should be returned.
     * @return the output of id (either an integer string or name of the user/group) or empty if an error occurs.
     */
    private static Optional<String> id(String id, IdOption option, boolean loadName) {
        return Optional.empty();
    }

    /**
     * Load the current user once.
     *
     * @return the current user
     * @throws IOException if an error occurs retrieving user or primary group information.
     */
    private synchronized AndroidUserAttributes loadCurrentUser() throws IOException {
        CURRENT_USER = AndroidUserAttributes.builder()
                .primaryGid(-2l)
                .principalName("test_user")
                .principalIdentifier("tester")
                .androidUserId(androidServiceLevelAPI)
                .build();
        return CURRENT_USER;
    }

    private AndroidUserAttributes lookupUser(String user) throws IOException {
        return AndroidUserAttributes.builder()
                .primaryGid(-2L)
                .principalName("test_user")
                .principalIdentifier("tester")
                .androidUserId(androidServiceLevelAPI)
                .build();
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private static AndroidGroupAttributes lookupGroup(String name) throws IOException {
        return AndroidGroupAttributes.builder().principalName(name).principalIdentifier(name).build();
    }

    @Override
    public boolean userExists(String user) {
        return true;
    }

    public AndroidUserAttributes lookupUserByIdentifier(String identifier) throws IOException {
        return lookupUser(identifier);
    }

    @Override
    public AndroidGroupAttributes lookupGroupByName(String group) throws IOException {
        return lookupGroup(group);
    }

    @Override
    public AndroidGroupAttributes lookupGroupByIdentifier(String identifier) throws IOException {
        return lookupGroup(identifier);
    }

    @Override
    public AndroidUserAttributes lookupCurrentUser() throws IOException {
        return loadCurrentUser();
    }

    @Override
    public Set<Integer> killProcessAndChildren(Process process, boolean force, Set<Integer> additionalPids,
                                               UserDecorator decorator)
            throws IOException, InterruptedException {
        logger.atWarn().log("Test kill process and children");
        Set<Integer> pids = getChildPids(process);
        if (additionalPids != null) {
            pids.addAll(additionalPids);
        }
        return pids;
    }

    private void killProcess(boolean force, UserDecorator decorator, Integer pid)
            throws IOException, InterruptedException {
        logger.atWarn().log("Test kill process");
    }

    @Override
    public ShellDecorator getShellDecorator() {
        return new ShDecorator();
    }

    @Override
    public int exitCodeWhenCommandDoesNotExist() {
        return 127;
    }

    @Override
    public String formatEnvironmentVariableCmd(String envVarName) {
        return "$" + envVarName;
    }

    @Override
    public UserDecorator getUserDecorator() {
        return new SudoDecorator();
    }

    @Override
    public String getPrivilegedGroup() {
        return PRIVILEGED_USER;
    }

    @Override
    public String getPrivilegedUser() {
        return PRIVILEGED_USER;
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
        runCmd("useradd -r -m " + user, o -> {
        }, "Failed to create user");
    }

    @Override
    public void createGroup(String group) throws IOException {
        runCmd("groupadd -r " + group, o -> {
        }, "Failed to create group");
    }

    @Override
    public void addUserToGroup(String user, String group) throws IOException {
        runCmd("usermod -a -G " + group + " " + user, o -> {
        }, "Failed to add user to group");
    }

    @Override
    protected void setOwner(UserPrincipal userPrincipal, GroupPrincipal groupPrincipal, Path path) throws IOException {
        logger.atWarn().log("Set owner");
    }

    @Getter
    public static class PosixFileSystemPermissionView extends FileSystemPermissionView {
        private Set<PosixFilePermission> posixFilePermissions;

        public PosixFileSystemPermissionView(FileSystemPermission permission) {
            super();
            posixFilePermissions = posixFilePermissions(permission);
        }

        /**
         * Convert to a set of PosixFilePermissions for use with Files.setPosixFilePermissions.
         *
         * @param permission Permission to convert
         * @return Set of permissions
         */
        public static Set<PosixFilePermission> posixFilePermissions(FileSystemPermission permission) {
            Set<PosixFilePermission> ret = EnumSet.noneOf(PosixFilePermission.class);

            if (permission.isOwnerRead()) {
                ret.add(PosixFilePermission.OWNER_READ);
            }
            if (permission.isOwnerWrite()) {
                ret.add(PosixFilePermission.OWNER_WRITE);
            }
            if (permission.isOwnerExecute()) {
                ret.add(PosixFilePermission.OWNER_EXECUTE);
            }
            if (permission.isGroupRead()) {
                ret.add(PosixFilePermission.GROUP_READ);
            }
            if (permission.isGroupWrite()) {
                ret.add(PosixFilePermission.GROUP_WRITE);
            }
            if (permission.isGroupExecute()) {
                ret.add(PosixFilePermission.GROUP_EXECUTE);
            }
            if (permission.isOtherRead()) {
                ret.add(PosixFilePermission.OTHERS_READ);
            }
            if (permission.isOtherWrite()) {
                ret.add(PosixFilePermission.OTHERS_WRITE);
            }
            if (permission.isOtherExecute()) {
                ret.add(PosixFilePermission.OTHERS_EXECUTE);
            }

            return ret;
        }
    }

    @Override
    public SystemResourceController getSystemResourceController() {
        return systemResourceController;
    }

    @Override
    public Exec createNewProcessRunner() {
        return new AndroidExec();
    }

    @Override
    protected FileSystemPermissionView getFileSystemPermissionView(FileSystemPermission permission, Path path) {
        return new PosixFileSystemPermissionView(permission);
    }

    @Override
    protected void setMode(FileSystemPermissionView permissionView, Path path) throws IOException {
        logger.atWarn().log("Set mode");
    }

    /**
     * Run a arbitrary command.
     * @param cmdStr command string
     * @param out output consumer
     * @param msg error string
     * @throws IOException IO exception
     */
    public void runCmd(String cmdStr, Consumer<CharSequence> out, String msg)
            throws IOException {
        logger.atWarn().log("Run: {}", cmdStr);
    }

    /**
     * Get the child PIDs of a process.
     * @param process process
     * @return a set of PIDs
     * @throws IOException IO exception
     * @throws InterruptedException InterruptedException
     */
    public Set<Integer> getChildPids(Process process) throws IOException, InterruptedException {
        logger.atWarn().log("Get test android child processes");
        return new HashSet<Integer>();
    }

    private List<String> children(String parent, Map<String, List<String>> procMap) {
        ArrayList<String> ret = new ArrayList<>();
        if (procMap.containsKey(parent)) {
            ret.addAll(procMap.get(parent));
            procMap.get(parent).forEach(p -> ret.addAll(children(p, procMap)));
        }
        return ret;
    }

    private String getIpcServerSocketAbsolutePath(Path rootPath) {
        return rootPath.resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();
    }

    private boolean isSocketPathTooLong(String socketPath) {
        return socketPath.length() >= UDS_SOCKET_PATH_MAX_LEN;
    }

    @Override
    public String prepareIpcFilepath(Path rootPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath);

        if (Files.exists(Paths.get(ipcServerSocketAbsolutePath))) {
            try {
                logger.atDebug().log("Deleting the ipc server socket descriptor file");
                Files.delete(Paths.get(ipcServerSocketAbsolutePath));
            } catch (IOException e) {
                logger.atError().setCause(e).kv("path", ipcServerSocketAbsolutePath)
                        .log("Failed to delete the ipc server socket descriptor file");
            }
        }

        return ipcServerSocketAbsolutePath;
    }

    @Override
    public String prepareIpcFilepathForComponent(Path rootPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath);

        boolean symLinkCreated = false;

        try {
            // Usually we do not want to write outside of kernel root. Because of socket path length limitations we
            // will create a symlink only if needed
            if (isSocketPathTooLong(ipcServerSocketAbsolutePath)) {
                Files.createSymbolicLink(Paths.get(NUCLEUS_ROOT_PATH_SYMLINK), rootPath);
                symLinkCreated = true;
            }
        } catch (IOException e) {
            logger.atError().setCause(e).log("Cannot setup symlinks for the ipc server socket path. Cannot start "
                    + "IPC server as the long nucleus root path is making socket filepath greater than 108 chars. "
                    + "Shorten root path and start nucleus again");
            cleanupIpcFiles(rootPath);
            throw new RuntimeException(e);
        }

        return symLinkCreated ? IPC_SERVER_DOMAIN_SOCKET_RELATIVE_FILENAME : ipcServerSocketAbsolutePath;
    }

    @Override
    public String prepareIpcFilepathForRpcServer(Path rootPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath);
        return isSocketPathTooLong(ipcServerSocketAbsolutePath) ? IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK :
                ipcServerSocketAbsolutePath;
    }

    @Override
    public void setIpcFilePermissions(Path rootPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath);

        // IPC socket does not get created immediately after runServer returns
        // Wait up to 30s for it to exist
        Path ipcPath = Paths.get(ipcServerSocketAbsolutePath);
        long maxTime = System.currentTimeMillis() + MAX_IPC_SOCKET_CREATION_WAIT_TIME_SECONDS * 1000;
        while (System.currentTimeMillis() < maxTime && Files.notExists(ipcPath)) {
            logger.atDebug().log("Waiting for server socket file");
            try {
                Thread.sleep(SOCKET_CREATE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                logger.atWarn().setCause(e).log("Service interrupted before server socket exists");
                cleanupIpcFiles(rootPath);
                throw new RuntimeException(e);
            }
        }

        // set permissions on IPC socket so that everyone can read/write
        try {
            Permissions.setIpcSocketPermission(ipcPath);
        } catch (IOException e) {
            logger.atError().setCause(e).log("Error while setting permissions for IPC server socket");
            cleanupIpcFiles(rootPath);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cleanupIpcFiles(Path rootPath) {
        if (Files.exists(Paths.get(IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK), LinkOption.NOFOLLOW_LINKS)) {
            try {
                logger.atDebug().log("Deleting the ipc server socket descriptor file symlink");
                Files.delete(Paths.get(IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Failed to delete the ipc server socket descriptor file symlink");
            }
        }

        // Removing it during close as CWD might change on next run
        if (Files.exists(Paths.get(NUCLEUS_ROOT_PATH_SYMLINK), LinkOption.NOFOLLOW_LINKS)) {
            try {
                logger.atDebug().log("Deleting the nucleus root path symlink");
                Files.delete(Paths.get(NUCLEUS_ROOT_PATH_SYMLINK));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Failed to delete the ipc server socket descriptor file symlink");
            }
        }

        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath);
        if (Files.exists(Paths.get(ipcServerSocketAbsolutePath))) {
            try {
                logger.atDebug().log("Deleting the ipc server socket descriptor file");
                Files.delete(Paths.get(ipcServerSocketAbsolutePath));
            } catch (IOException e) {
                logger.atError().setCause(e).log("Failed to delete the ipc server socket descriptor file");
            }
        }
    }

    @Override
    public String loaderFilename() {
        return "loader";
    }

    @Override
    public AndroidPackageManager getAndroidPackageManager() {
        return androidAppLevelAPI;
    }

    @Override
    public AndroidComponentManager getAndroidComponentManager() {
        return androidServiceLevelAPI;
    }

    private enum IdOption {
        User, Group
    }

    /**
     * Decorate a command to run in a shell.
     */
    public static class ShDecorator implements ShellDecorator {
        private static final String DEFAULT_SHELL = "sh";
        private static final String DEFAULT_ARG = "-c";
        private String shell;
        private final String arg;

        /**
         * Create a new instance using the default shell (sh).
         */

        public ShDecorator() {
            this(DEFAULT_SHELL, DEFAULT_ARG);
        }

        /**
         * Create a new instance for a given shell command and shell argument for taking in string input.
         *
         * @param shell the shell.
         * @param arg   optional argument for passing string data into the shell.
         */
        public ShDecorator(String shell, String arg) {
            this.shell = shell;
            this.arg = arg;
        }

        @Override
        public String[] decorate(String... command) {
            boolean hasArg = !Utils.isEmpty(arg);
            int size = hasArg ? 3 : 2;
            String[] ret = new String[size];
            ret[0] = Utils.isEmpty(shell) ? DEFAULT_SHELL : shell;
            if (hasArg) {
                ret[1] = arg;
            }
            ret[size - 1] = String.join(" ", command);
            return ret;
        }

        @Override
        public ShellDecorator withShell(String shell) {
            this.shell = shell;
            return this;
        }
    }

    /**
     * Decorator for running a command as a different user/group with `sudo`.
     */
    @NoArgsConstructor
    public class SudoDecorator extends UserDecorator {
        @Override
        public String[] decorate(String... command) {
            // do nothing if no user set
            if (user == null) {
                return command;
            }

            try {
                loadCurrentUser();
            } catch (IOException e) {
                // ignore error here - it shouldn't happen and in worst case it will sudo to current user
            }

            // no sudo necessary if running as current user
            if (CURRENT_USER != null && CURRENT_USER_PRIMARY_GROUP != null
                    && (CURRENT_USER.getPrincipalName().equals(user)
                    || CURRENT_USER.getPrincipalIdentifier().equals(user))
                    && (group == null
                    || CURRENT_USER_PRIMARY_GROUP.getPrincipalIdentifier().equals(group)
                    || CURRENT_USER_PRIMARY_GROUP.getPrincipalName().equals(group))) {
                return command;
            }

            int size = (group == null) ? 7 : 9;
            String[] ret = new String[command.length + size];
            ret[0] = "sudo";
            ret[1] = "-n";  // don't prompt for password
            ret[2] = "-E";  // pass env vars through
            ret[3] = "-H";  // set $HOME
            ret[4] = "-u";  // set user
            if (user.chars().allMatch(Character::isDigit)) {
                user = "#" + user;
            }
            ret[5] = user;
            if (group != null) {
                ret[6] = "-g"; // set group
                if (group.chars().allMatch(Character::isDigit)) {
                    group = "#" + group;
                }
                ret[7] = group;
            }
            ret[size - 1] = "--";
            System.arraycopy(command, 0, ret, size, command.length);
            return ret;
        }
    }
}
