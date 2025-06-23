/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.logging.api.LogEventBuilder;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.Permissions;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.ShellDecorator;
import com.aws.greengrass.util.platforms.StubResourceController;
import com.aws.greengrass.util.platforms.SystemResourceController;
import com.aws.greengrass.util.platforms.UserDecorator;
import com.sun.jna.platform.unix.LibC;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OperatingSystem.OSVersionInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.aws.greengrass.util.Utils.inputStreamToString;

/**
 * Unix specific platform implementation.
 */
public class UnixPlatform extends Platform {

    public static final String LOADER_LOGS_FILE_NAME = "loader.log";
    public static final Pattern PS_PID_PATTERN = Pattern.compile("(\\d+)\\s+(\\d+)");
    public static final String PRIVILEGED_USER = "root";
    public static final String STDOUT = "stdout";
    public static final String STDERR = "stderr";
    protected static final int SIGTERM = 15;
    protected static final int SIGKILL = 9;
    private static final String POSIX_GROUP_FILE = "/etc/group";

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

    private static UnixUserAttributes CURRENT_USER;
    private static UnixGroupAttributes CURRENT_USER_PRIMARY_GROUP;
    private static final Lock lock = LockFactory.newReentrantLock(UnixPlatform.class.getSimpleName());

    private final SystemResourceController systemResourceController = new StubResourceController();
    private final UnixRunWithGenerator runWithGenerator;
    private final SystemInfo oshiSystemInfo = new SystemInfo();
    private final OperatingSystem oshiOs = oshiSystemInfo.getOperatingSystem();
    private final OSVersionInfo oshiVersionInfo = oshiOs.getVersionInfo();

    /**
     * Construct a new instance.
     */
    public UnixPlatform() {
        super();
        // avoid spamming DEBUG-level oshi logs when reading process stats
        LogManager.getLogger(oshi.util.FileUtil.class.getName()).setLevel("INFO");
        runWithGenerator = new UnixRunWithGenerator(this);
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
        if (option == IdOption.Group) {
            logEvent.kv("group", id);
        } else if (option == IdOption.User && !loadSelf) {
            logEvent.kv("user", id);
        }
        logEvent.kv(STDOUT, out).kv(STDERR, err).setCause(cause).log("Error while looking up id"
                + (loadSelf ? " for current user" : ""));

        return Optional.empty();
    }

    /**
     * Load the current user once.
     *
     * @return the current user
     * @throws IOException if an error occurs retrieving user or primary group information.
     */
    @SuppressWarnings("PMD.NonThreadSafeSingleton") // this is threadsafe
    @SuppressFBWarnings(value = "LI_LAZY_INIT_STATIC", justification = "We are properly locking")
    private static UnixUserAttributes loadCurrentUser() throws IOException {
        try (LockScope ls = LockScope.lock(lock)) {
            if (CURRENT_USER == null) {
                int id = LibC.INSTANCE.geteuid();
                UnixUserAttributes.UnixUserAttributesBuilder builder =
                        UnixUserAttributes.builder().principalIdentifier(String.valueOf(id))
                                .principalName(System.getProperty("user.name"));

                long group = LibC.INSTANCE.getegid();
                CURRENT_USER = builder.primaryGid(group).build();
                CURRENT_USER_PRIMARY_GROUP = lookupGroup(String.valueOf(group));
            }
            return CURRENT_USER;
        }
    }

    private static UnixUserAttributes lookupUser(String user) throws IOException {
        if (Utils.isEmpty(user)) {
            throw new IOException("No user to lookup");
        }
        boolean isNumeric = user.chars().allMatch(Character::isDigit);
        UnixUserAttributes.UnixUserAttributesBuilder builder = UnixUserAttributes.builder();

        if (isNumeric) {
            builder.principalIdentifier(user);
        }
        builder.principalName(user);
        Optional<String> id = id(user, IdOption.User, isNumeric);
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

        id(user, IdOption.Group, false).ifPresent(s -> builder.primaryGid(Long.parseLong(s)));
        return builder.build();
    }

    @SuppressWarnings("PMD.AssignmentInOperand")
    private static UnixGroupAttributes lookupGroup(String name) throws IOException {
        if (Utils.isEmpty(name)) {
            throw new IOException("No group to lookup");
        }
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(POSIX_GROUP_FILE))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split(":");
                if (parts.length < 3) {
                    throw new IOException(
                            String.format("Unrecognized %s file. Expected syntax: name:passwd:gid[:userlist]. Got: %s",
                                    POSIX_GROUP_FILE, line));
                }
                if (name.equals(parts[0]) || name.equals(parts[2])) {
                    return UnixGroupAttributes.builder().principalName(parts[0]).principalIdentifier(parts[2]).build();
                }
            }
        }

        // if customer put in an ID it does not need to exist on the system
        if (name.chars().allMatch(Character::isDigit)) {
            return UnixGroupAttributes.builder().principalName(name).principalIdentifier(name).build();
        }
        throw new IOException("Unrecognized group: " + name);
    }

    @Override
    public boolean userExists(String user) {
        try {
            lookupUser(user);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public UnixUserAttributes lookupUserByIdentifier(String identifier) throws IOException {
        return lookupUser(identifier);
    }

    @Override
    public UnixGroupAttributes lookupGroupByName(String group) throws IOException {
        return lookupGroup(group);
    }

    @Override
    public UnixGroupAttributes lookupGroupByIdentifier(String identifier) throws IOException {
        return lookupGroup(identifier);
    }

    @Override
    public UnixUserAttributes lookupCurrentUser() throws IOException {
        return loadCurrentUser();
    }

    @Override
    public Set<Integer> killProcessAndChildren(Process process, boolean force, Set<Integer> additionalPids,
                                               UserDecorator decorator)
            throws IOException, InterruptedException {
        PidProcess parentPidProcess = Processes.newPidProcess(process);

        logger.atInfo().log("Killing child processes of pid {}, force is {}", parentPidProcess.getPid(), force);
        Set<Integer> pids;
        try {
            pids = getChildPids(process);
            logger.atDebug().log("Found children of {}. {}", parentPidProcess.getPid(), pids);
            if (additionalPids != null) {
                pids.addAll(additionalPids);
            }

            for (Integer pid : pids) {
                if (!Processes.newPidProcess(pid).isAlive()) {
                    continue;
                }

                killProcess(force, decorator, pid);
            }

            logger.atInfo().log("Killing parent process {}, force is {}", parentPidProcess.getPid(), force);
            killProcess(force, decorator, parentPidProcess.getPid());
        } finally {
            // calling process.destroy() here when force==false will cause the child process (component process) to be
            // terminated immediately. This prevents the component process from shutting down gracefully.
            if (force && process.isAlive()) {
                process.destroyForcibly();
                if (process.isAlive()) {
                    // Kill parent process using privileged user since the parent process might be sudo which a
                    // non-privileged user can't kill
                    killProcess(true, getUserDecorator().withUser(getPrivilegedUser()), parentPidProcess.getPid());
                }
            }
        }

        // add parent pid so caller can validate if every process is properly terminated
        pids.add(parentPidProcess.getPid());
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
    public UnixRunWithGenerator getRunWithGenerator() {
        return runWithGenerator;
    }

    @Override
    public void createUser(String user) throws IOException {
        try {
            runCmd("useradd -r -m " + user, o -> {
            }, "Failed to create user with useradd");
        } catch (IOException e) {
            try {
                runCmd("adduser -S  " + user, l -> {
                }, "Failed to create user with adduser");
            } catch (IOException ee) {
                e.addSuppressed(ee);
                throw e;
            }
        }
    }

    @Override
    public void createGroup(String group) throws IOException {
        try {
            runCmd("groupadd -r " + group, o -> {
            }, "Failed to create group with groupadd");
        } catch (IOException e) {
            try {
                runCmd("addgroup -S " + group, l -> {
                }, "Failed to create group with addgroup");
            } catch (IOException ee) {
                e.addSuppressed(ee);
                throw e;
            }
        }
    }

    @Override
    public void addUserToGroup(String user, String group) throws IOException {
        try {
            runCmd("usermod -a -G " + group + " " + user, o -> {
            }, "Failed to add user to group with usermod");
        } catch (IOException e) {
            try {
                runCmd("addgroup " + user + " " + group, l -> {
                }, "Failed to add user to group with addgroup");
            } catch (IOException ee) {
                e.addSuppressed(ee);
                throw e;
            }
        }
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
        return new UnixExec();
    }

    @Override
    protected FileSystemPermissionView getFileSystemPermissionView(FileSystemPermission permission, Path path) {
        return new PosixFileSystemPermissionView(permission);
    }

    @Override
    protected void setMode(FileSystemPermissionView permissionView, Path path) throws IOException {
        if (permissionView instanceof PosixFileSystemPermissionView) {
            PosixFileSystemPermissionView posixFileSystemPermissionView =
                    (PosixFileSystemPermissionView) permissionView;
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
        try (Exec exec = getInstance().createNewProcessRunner()) {
            StringBuilder output = new StringBuilder();
            StringBuilder error = new StringBuilder();
            Optional<Integer> exit = exec.withExec(cmdStr.split(" "))
                    .withShell()
                    .withOut(o -> {
                        out.accept(o);
                        output.append(o);
                    }).withErr(error::append).exec();
            if (!exit.isPresent() || exit.get() != 0) {
                throw new IOException(String.format(
                        String.format("%s - command: %s, output: %s , error: %s ", msg, cmdStr, output.toString(),
                                error.toString())));
            }
        } catch (InterruptedException | IOException e) {
            throw new IOException(String.format("%s , command : %s", msg, cmdStr), e);
        }
    }

    /**
     * Get the child PIDs of a process.
     * @param process process
     * @return a set of PIDs
     * @throws InterruptedException InterruptedException
     */
    public Set<Integer> getChildPids(Process process) throws InterruptedException {
        PidProcess pp = Processes.newPidProcess(process);

        logger.atTrace().log("Identifying child processes of pid {}", pp.getPid());
        // no filtering, sorting, or limits
        return oshiOs.getDescendantProcesses(pp.getPid(), null, null, 0)
                .stream().map(OSProcess::getProcessID)
                .collect(Collectors.toSet());
    }

    private String getIpcServerSocketAbsolutePath(Path rootPath, Path ipcPath) {
        if (ipcPath == null) {
            return rootPath.resolve(IPC_SERVER_DOMAIN_SOCKET_FILENAME).toString();
        }
        return ipcPath.toString();
    }

    private boolean isSocketPathTooLong(String socketPath) {
        return socketPath.length() >= UDS_SOCKET_PATH_MAX_LEN;
    }

    @Override
    public String prepareIpcFilepath(Path rootPath, Path ipcPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath, ipcPath);

        if (ipcPath != null) {
            try {
                Path parent = ipcPath.toAbsolutePath().getParent();
                Utils.createPaths(parent);
            } catch (IOException e) {
                logger.atError().setCause(e).kv("path", ipcServerSocketAbsolutePath)
                        .log("Failed to create the ipc socket path");
            }
        }

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
    public String prepareIpcFilepathForComponent(Path rootPath, Path ipcPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath, ipcPath);

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
            cleanupIpcFiles(rootPath, ipcPath);
            throw new RuntimeException(e);
        }

        return symLinkCreated ? IPC_SERVER_DOMAIN_SOCKET_RELATIVE_FILENAME : ipcServerSocketAbsolutePath;
    }

    @Override
    public String prepareIpcFilepathForRpcServer(Path rootPath, Path ipcPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath, ipcPath);
        return isSocketPathTooLong(ipcServerSocketAbsolutePath) ? IPC_SERVER_DOMAIN_SOCKET_FILENAME_SYMLINK :
                ipcServerSocketAbsolutePath;
    }

    @Override
    public void setIpcFilePermissions(Path rootPath, Path ipcPath) {
        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath, ipcPath);

        // IPC socket does not get created immediately after runServer returns
        // Wait up to 30s for it to exist
        Path ipcSocketPath = Paths.get(ipcServerSocketAbsolutePath);
        long maxTime = System.currentTimeMillis() + MAX_IPC_SOCKET_CREATION_WAIT_TIME_SECONDS * 1000;
        while (System.currentTimeMillis() < maxTime && Files.notExists(ipcSocketPath)) {
            logger.atDebug().log("Waiting for server socket file");
            try {
                Thread.sleep(SOCKET_CREATE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                logger.atWarn().setCause(e).log("Service interrupted before server socket exists");
                cleanupIpcFiles(rootPath, ipcPath);
                throw new RuntimeException(e);
            }
        }

        // set permissions on IPC socket so that everyone can read/write
        try {
            Permissions.setIpcSocketPermission(ipcSocketPath);
        } catch (IOException e) {
            logger.atError().setCause(e).log("Error while setting permissions for IPC server socket");
            cleanupIpcFiles(rootPath, ipcPath);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void cleanupIpcFiles(Path rootPath, Path ipcPath) {
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

        String ipcServerSocketAbsolutePath = getIpcServerSocketAbsolutePath(rootPath, ipcPath);
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

    @SuppressWarnings("PMD.DoubleBraceInitialization")
    @Override
    public Map<String, Object> getOSAndKernelMetrics() {
        return new HashMap<String, Object>() {{
            put("OSName", oshiOs.getFamily());
            put("OSVersion", oshiVersionInfo.getVersion());
            put("KernelVersion", oshiVersionInfo.getBuildNumber());
            put("CPUArchitecture", oshiSystemInfo.getHardware().getProcessor().getProcessorIdentifier()
                    .getMicroarchitecture());
        }};
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
    public static class SudoDecorator extends UserDecorator {
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
