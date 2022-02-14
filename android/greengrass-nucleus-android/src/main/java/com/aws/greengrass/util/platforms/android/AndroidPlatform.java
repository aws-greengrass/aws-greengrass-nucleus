/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import static com.aws.greengrass.util.Utils.inputStreamToString;

import com.aws.greengrass.logging.api.LogEventBuilder;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Permissions;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.ShellDecorator;
import com.aws.greengrass.util.platforms.StubResourceController;
import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.UserDecorator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.io.SocketOptions;

// FIXME: android: to be implemented
/**
 * Android specific platform implementation.
 */
public class AndroidPlatform extends Platform {
    public static final Pattern PS_PID_PATTERN = Pattern.compile("(\\d+)\\s+(\\d+)");

    public static final String PRIVILEGED_USER = "root";
    public static final String STDOUT = "stdout";
    public static final String STDERR = "stderr";
    protected static final int SIGNULL = 0;
    protected static final int SIGKILL = 9;
    protected static final int SIGTERM = 15;

    public static final String IPC_SERVER_NETWORK_SOCKET_ADDR = "127.0.0.1";
    public static final String NUCLEUS_ROOT_PATH_SYMLINK = "./nucleusRoot";
    // This is relative to component's CWD
    // components CWD is <kernel-root-path>/work/component

    private static AndroidUserAttributes CURRENT_USER;
    private static AndroidGroupAttributes CURRENT_USER_PRIMARY_GROUP;

    private final SystemResourceController systemResourceController = new StubResourceController();
    private final AndroidRunWithGenerator runWithGenerator;

    private AndroidAppLevelAPI androidAppLevelAPI;
    private AndroidServiceLevelAPI androidServiceLevelAPI;

    /**
     * Construct a new instance.
     */
    public AndroidPlatform() {
        super();
        runWithGenerator = new AndroidRunWithGenerator(this);
    }

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
    private static Optional<String> id(String id, AndroidPlatform.IdOption option, boolean loadName) {
        boolean loadSelf = Utils.isEmpty(id);
        String[] cmd = new String[2 + (loadName ? 1 : 0) + (loadSelf ? 0 : 1)];
        int i = 0;
        cmd[i] = "id";
        switch (option) {
            case Group:
                cmd[++i] = "-g";
                break;
            case User:
                cmd[++i] = "-u";
                break;
            default:
                logger.atDebug().setEventType("id-lookup")
                        .addKeyValue("option", option)
                        .log("invalid option provided for id");
                return Optional.empty();
        }
        if (loadName) {
            cmd[++i] = "-n";
        }
        if (!loadSelf) {
            cmd[++i] = id;
        }

        logger.atTrace().setEventType("id-lookup").kv("command", String.join(" ", cmd)).log();
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();

        Throwable cause = null;
        try (Exec exec = getInstance().createNewProcessRunner()) {
            Optional<Integer> exit = exec.withExec(cmd).withShell().withOut(out::append).withErr(err::append).exec();
            if (exit.isPresent() && exit.get() == 0) {
                return Optional.of(out.toString().trim());
            }
        } catch (InterruptedException e) {
            Arrays.stream(e.getSuppressed()).forEach((t) -> {
                logger.atError().setCause(e).log("interrupted");
            });
            cause = e;
        } catch (IOException e) {
            cause = e;
        }
        LogEventBuilder logEvent = logger.atError().setEventType("id-lookup");
        if (option == AndroidPlatform.IdOption.Group) {
            logEvent.kv("group", id);
        } else if (option == AndroidPlatform.IdOption.User && !loadSelf) {
            logEvent.kv("user", id);
        }
        logger.atError().setCause(cause).log("Error while looking up id"
                + (loadSelf ? " for current user" : ""));

        return Optional.empty();
    }

    /**
     * Load the current user once.
     *
     * @return the current user
     * @throws IOException if an error occurs retrieving user or primary group information.
     */
    private synchronized AndroidUserAttributes loadCurrentUser() throws IOException {
        if (CURRENT_USER == null) {
            Optional<String> id = id(null, AndroidPlatform.IdOption.User, false);
            id.orElseThrow(() -> new IOException("Could not lookup current user: " + System.getProperty("user.name")));

            Optional<String> name = id(null, AndroidPlatform.IdOption.User, true);

            AndroidUserAttributes.AndroidUserAttributesBuilder builder = AndroidUserAttributes.builder()
                    .principalIdentifier(id.get())
                    .principalName(name.orElse(id.get()));

            Optional<String> groupId = id(null, AndroidPlatform.IdOption.Group, false);
            groupId.orElseThrow(() -> new IOException("Could not lookup primary group for current user: " + id.get()));
            Optional<String> groupName = id(null, AndroidPlatform.IdOption.Group, true);

            CURRENT_USER = builder.primaryGid(Long.parseLong(groupId.get())).build();
            CURRENT_USER_PRIMARY_GROUP = AndroidGroupAttributes.builder().
                    principalName(groupName.get()).principalIdentifier(groupId.get()).build();
        }
        return CURRENT_USER;
    }

    private AndroidUserAttributes lookupUser(String user) throws IOException {
        if (Utils.isEmpty(user)) {
            throw new IOException("No user to lookup");
        }
        boolean isNumeric = user.chars().allMatch(Character::isDigit);
        AndroidUserAttributes.AndroidUserAttributesBuilder builder = AndroidUserAttributes.builder();

        if (isNumeric) {
            builder.principalIdentifier(user);
        }
        builder.principalName(user);
        Optional<String> id = id(user, AndroidPlatform.IdOption.User, isNumeric);
        if (id.isPresent()) {
            if (isNumeric) {
                builder.principalName(id.get());
            } else {
                builder.principalIdentifier(id.get());
            }
        } else {
            if (!isNumeric) {
                throw new IOException("Unrecognized user: " + user);
            }
        }

        id(user, AndroidPlatform.IdOption.Group, false).ifPresent(s -> builder.primaryGid(Long.parseLong(s)));
        return builder.build();
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private static AndroidGroupAttributes lookupGroup(String name) throws IOException {
        if (Utils.isEmpty(name)) {
            throw new IOException("No group to lookup");
        }
        if (CURRENT_USER_PRIMARY_GROUP.getClass()
                .equals(name)) {
            return CURRENT_USER_PRIMARY_GROUP;
        } else {
            throw new IOException("Non primary group is not supported");
        }
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
        Integer ppid = -1;
        try {
            Field f = process.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            ppid = (int) f.getLong(process);
            f.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException cause) {
            logger.atError().setCause(cause).log("Failed to get process pid");
            throw new InterruptedException("Failed to get process pid");
        }

        logger.atInfo().log("Killing child processes of pid {}, force is {}", ppid, force);
        Set<Integer> pids;
        try {
            pids = getChildPids(ppid);
            logger.atDebug().log("Found children of {}. {}", ppid, pids);
            if (additionalPids != null) {
                pids.addAll(additionalPids);
            }

            for (Integer pid : pids) {
                if (!isProcessAlive(pid)) {
                    continue;
                }

                killProcess(force, decorator, pid);
            }
        } finally {
            // calling process.destroy() here when force==false will cause the child process (component process) to be
            // terminated immediately. This prevents the component process from shutting down gracefully.
            if (force && process.isAlive()) {
                process.destroyForcibly();
                if (process.isAlive()) {
                    // Kill parent process using privileged user since the parent process might be sudo which a
                    // non-privileged user can't kill
                    killProcess(true, getUserDecorator().withUser(getPrivilegedUser()), ppid);
                }
            }
        }

        return pids;
    }

    private void killProcess(boolean force, UserDecorator decorator, Integer pid)
            throws IOException, InterruptedException {
        String[] cmd = {"kill", "-" + (force ? SIGKILL : SIGTERM), Integer.toString(pid)};
        if (decorator != null) {
            cmd = decorator.decorate(cmd);
        }
        logger.atDebug().log("Killing pid {} with signal {} using {}", pid,
                force ? SIGKILL : SIGTERM, String.join(" ", cmd));
        Process proc = Runtime.getRuntime().exec(cmd);
        proc.waitFor();
        if (proc.exitValue() != 0) {
            logger.atWarn().kv("pid", pid).kv("exit-code", proc.exitValue())
                    .kv(STDOUT, inputStreamToString(proc.getInputStream()))
                    .kv(STDERR, inputStreamToString(proc.getErrorStream()))
                    .log("kill exited non-zero (process not found or other error)");
        }
    }

    /**
     * Is process with given PID exist.
     * @param pid process pid
     * @return true if process exists
     * @throws IOException IO exception
     * @throws InterruptedException InterruptedException
     */
    public boolean isProcessAlive(Integer pid) throws IOException, InterruptedException {
        logger.atDebug().log("Running kill -0 to check process with pid {}", pid);
        Process proc = Runtime.getRuntime().exec(new String[]{"kill", "-" + SIGNULL, Integer.toString(pid)});
        proc.waitFor();
        return proc.exitValue() == 0;
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
        return new RunDecorator();
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
    public AndroidRunWithGenerator getRunWithGenerator() { return runWithGenerator; }

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
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);

        if (userPrincipal != null && !userPrincipal.equals(view.getOwner())) {
            logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH_LOG_KEY, path)
                    .kv("owner", userPrincipal.toString()).log();
            view.setOwner(userPrincipal);
        }

        if (groupPrincipal != null) {
            logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH_LOG_KEY, path)
                    .kv("group", groupPrincipal.toString()).log();
            view.setGroup(groupPrincipal);
        }
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
        if (permissionView instanceof AndroidPlatform.PosixFileSystemPermissionView) {
            AndroidPlatform.PosixFileSystemPermissionView posixFileSystemPermissionView =
                    (AndroidPlatform.PosixFileSystemPermissionView) permissionView;
            Set<PosixFilePermission> permissions = posixFileSystemPermissionView.getPosixFilePermissions();

            PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);

            Set<PosixFilePermission> currentPermission = view.readAttributes().permissions();
            if (!currentPermission.equals(permissions)) {
                logger.atTrace().setEventType(SET_PERMISSIONS_EVENT).kv(PATH_LOG_KEY, path).kv("perm",
                        PosixFilePermissions.toString(permissions)).log();
                view.setPermissions(permissions);
            }
        }
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
     * @param pid process pid
     * @return a set of PIDs
     * @throws IOException IO exception
     * @throws InterruptedException InterruptedException
     */
    public Set<Integer> getChildPids(Integer pid) throws IOException, InterruptedException {
        // Use PS to list process PID and parent PID so that we can identify the process tree
        logger.atDebug().log("Running ps to identify child processes of pid {}", pid);
        Process proc = Runtime.getRuntime().exec(new String[]{"ps", "-a", "-o", "pid,ppid"});
        proc.waitFor();
        if (proc.exitValue() != 0) {
            logger.atWarn().kv("pid", pid).kv("exit-code", proc.exitValue())
                    .kv(STDOUT, inputStreamToString(proc.getInputStream()))
                    .kv(STDERR, inputStreamToString(proc.getErrorStream())).log("ps exited non-zero");
            throw new IOException("ps exited with " + proc.exitValue());
        }

        try (InputStreamReader reader = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8);
             BufferedReader br = new BufferedReader(reader)) {
            Stream<String> lines = br.lines();
            Map<String, String> pidToParent = lines.map(s -> {
                Matcher matches = PS_PID_PATTERN.matcher(s.trim());
                if (matches.matches()) {
                    return new Pair<>(matches.group(1), matches.group(2));
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toMap(Pair::getLeft, Pair::getRight));

            Map<String, List<String>> parentToChildren = Utils.inverseMap(pidToParent);
            List<String> childProcesses = children(Integer.toString(pid), parentToChildren);

            return childProcesses.stream().map(Integer::parseInt).collect(Collectors.toSet());
        }
    }

    private List<String> children(String parent, Map<String, List<String>> procMap) {
        ArrayList<String> ret = new ArrayList<>();
        if (procMap.containsKey(parent)) {
            ret.addAll(procMap.get(parent));
            procMap.get(parent).forEach(p -> ret.addAll(children(p, procMap)));
        }
        return ret;
    }

    private String getIpcServerSocketAddress() {
        return IPC_SERVER_NETWORK_SOCKET_ADDR;
    }

    @Override
    public SocketOptions prepareIpcSocketOptions() {
        SocketOptions socketOptions = new SocketOptions();
        socketOptions.connectTimeoutMs = 3000;
        socketOptions.domain = SocketOptions.SocketDomain.IPv4;
        socketOptions.type = SocketOptions.SocketType.STREAM;

        return socketOptions;
    }

    @Override
    public int prepareIpcSocketPort(final int defaultPort) {
        int portNumber = defaultPort;
        try {
            String portString = System.getProperty("ipc.socket.port");
            if (portString != null) {
                portNumber = Integer.valueOf(portString);
            } else {
                throw new Exception("Parameters do not contain \"ipc.socket.port\" key");
            }
        } catch (NumberFormatException e) {
            String errorMessage = "IPC port number from the parameters has invalid number format. " +
                    "A default value will be used instead.";
            logger.atDebug().setCause(e).log(errorMessage);
        } catch(Exception e) {
            String errorMessage = "Unable to obtain IPC port number from parameters. " +
                    "A default value will be used instead.";
            logger.atDebug().setCause(e).log(errorMessage);
        }
        return portNumber;
    }

    @Override
    public String prepareIpcFilepath(Path rootPath) {
        // rootPath is not used in Android since IPC is based on Network Sockets */
        String ipcServerSocketAbsolutePath = getIpcServerSocketAddress();
        return ipcServerSocketAbsolutePath;
    }

    @Override
    public String prepareIpcFilepathForComponent(Path rootPath) {
        // rootPath is not used in Android since IPC is based on Network Sockets */
        String ipcServerSocketAbsolutePath = getIpcServerSocketAddress();
        return ipcServerSocketAbsolutePath;
    }

    @Override
    public String prepareIpcFilepathForRpcServer(Path rootPath) {
        // rootPath is not used in Android since IPC is based on Network Sockets */
        String ipcServerSocketAbsolutePath = getIpcServerSocketAddress();
        return ipcServerSocketAbsolutePath;
    }

    @Override
    public void setIpcFilePermissions(Path rootPath) {
        // Android uses Network Sockets for IPC, there's no need to set permissions */
        logger.atDebug().log("IPC file permissions change ignored");
    }

    @Override
    public void cleanupIpcFiles(Path rootPath) {
        // Android uses Network Sockets for IPC, there's no need to clean anything */
        logger.atDebug().log("IPC file cleanup ignored");
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
     * Decorator for running a command.
     */
    @NoArgsConstructor
    public static class RunDecorator extends UserDecorator {
        @Override
        public String[] decorate(String... command) {
            // Decorate does nothing
            return command;
        }
    }
}
