/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.ShellDecorator;
import com.aws.greengrass.util.platforms.UserDecorator;
import lombok.Getter;
import org.zeroturnaround.process.PidProcess;
import org.zeroturnaround.process.Processes;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Vaguely like ProcessBuilder, but more flexible and lambda-friendly.
 * <pre>
 * // set wd to current working directory
 * String wd = Exec.sh("pwd");
 *
 * // run a shell in the background, and print "Yahoo!"
 * // when "wifi" appears in the system log
 * new Exec().withShell("tail -F /var/log/system.log")
 * .withOut(str->{
 * if(str.toString().contains("wifi"))
 * System.out.println("Yahoo!");
 * })
 * .background(exc -> System.out.println("exit "+exc));
 * </pre>
 */
@SuppressWarnings("PMD.AvoidCatchingThrowable")
public final class Exec implements Closeable {
    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("wind");
    private static final Logger staticLogger = LogManager.getLogger(Exec.class);
    private static final Consumer<CharSequence> NOP = s -> {
    };

    // default directory relative paths are resolved against (i.e. current working directory)
    private static final File userdir = new File(System.getProperty("user.dir"));

    private static final ConcurrentLinkedDeque<Path> paths = new ConcurrentLinkedDeque<>();
    private static String[] defaultEnvironment = {"PATH=" + System.getenv("PATH"), "JAVA_HOME=" + System.getProperty(
            "java.home"), "HOME=" + System.getProperty("user.home")};

    static {
        addPathEntries(System.getenv("PATH"));
        try {
            if (!isWindows) {
                // This bit is gross: under some circumstances (like IDEs launched from the
                // macos Dock) the PATH environment variable doesn't match the path one expects
                // after the .profile script is executed.  Fire up a login shell, then grab it's
                // path variable, but without using Exec shorthands to avoid initialization
                // order paradoxes.
                Process hack =
                        Runtime.getRuntime().exec(new String[]{"sh", "-c", "echo 'echo $PATH' | grep -E ':[^ ]'"});
                StringBuilder path = new StringBuilder();

                Thread bg = new Thread(() -> {
                    try (InputStream in = hack.getInputStream()) {
                        for (int c = in.read(); c >= 0; c = in.read()) {
                            path.append((char) c);
                        }
                    } catch (Throwable ignore) {
                    }
                });
                bg.start();
                bg.join(2000);
                addPathEntries(path.toString().trim());
                // Ensure some level of sanity
                ensurePresent("/bin", "/usr/bin", "/sbin", "/usr/sbin");
            }
        } catch (Throwable ex) {
            staticLogger.atError().log("Error while initializing PATH", ex);
        }
        computePathString();
    }

    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    Process process;
    IntConsumer whenDone;
    Consumer<CharSequence> stdout = NOP;
    Consumer<CharSequence> stderr = NOP;
    AtomicInteger numberOfCopiers;
    private String[] environment = defaultEnvironment;
    private String[] cmds;

    private ShellDecorator shellDecorator;
    private UserDecorator userDecorator;

    private File dir = userdir;
    private long timeout = -1;
    private TimeUnit timeunit = TimeUnit.SECONDS;
    private Copier stderrc;
    private Copier stdoutc;
    private Logger logger = staticLogger;

    public static void setDefaultEnv(String key, CharSequence value) {
        defaultEnvironment = setenv(defaultEnvironment, key, value, false);
    }

    private static String[] setenv(String[] env, String key, CharSequence value, boolean forceCopy) {
        int elen = env.length;
        int klen = key.length();
        for (int i = 0; i < elen; i++) {
            String s = env[i];
            if (s.length() > klen && s.charAt(klen) == '=' && s.startsWith(key)) {
                if (forceCopy) {
                    env = Arrays.copyOf(env, env.length);
                }
                env[i] = key + '=' + value;
                return env;
            }
        }
        String[] ne = Arrays.copyOf(env, elen + 1, String[].class);
        ne[elen] = key + '=' + value;
        return ne;
    }

    public Exec setenv(String key, CharSequence value) {
        environment = setenv(environment, key, value, environment == defaultEnvironment);
        return this;
    }

    public Exec logger(Logger logger) {
        this.logger = logger;
        return this;
    }

    public static String cmd(String... command) throws InterruptedException, IOException {
        return new Exec().withExec(command).execAndGetStringOutput();
    }

    public static String sh(String command) throws InterruptedException, IOException {
        return sh((File) null, command);
    }

    public static String sh(File dir, String command) throws InterruptedException, IOException {
        return new Exec().cd(dir).withExec("sh", "-c", command).execAndGetStringOutput();
    }

    public static String sh(Path dir, String command) throws InterruptedException, IOException {
        return sh(dir.toFile(), command);
    }

    public static boolean successful(boolean ignoreStderr, String command) throws InterruptedException, IOException {
        return new Exec().withShell(command).successful(ignoreStderr);
    }

    public boolean successful(boolean ignoreStderr) throws InterruptedException, IOException {
        exec();
        return (ignoreStderr || stderrc.getNlines() == 0) && process.exitValue() == 0;
    }

    /**
     * Find the path of a given command.
     *
     * @param fn command to lookup.
     * @return the Path of the command, or null if not found.
     */
    @Nullable
    public static Path which(String fn) {  // mirrors shell command
        // [P41372857]: Add Windows support
        fn = deTilde(fn);
        if (fn.startsWith("/")) {
            Path f = Paths.get(fn);
            return Files.isExecutable(f) ? f : null;
        }
        for (Path d : paths) {
            Path f = d.resolve(fn);
            if (Files.isExecutable(f)) {
                return f;
            }
        }
        return null;
    }

    private static String deTilde(String s) {
        if (s.startsWith("~/")) {
            s = Utils.HOME_PATH.resolve(s.substring(2)).toString();
        }
        return s;
    }

    private static void ensurePresent(String... fns) {
        for (String fn : fns) {
            Path ulb = Paths.get(fn);
            if (Files.isDirectory(ulb) && !paths.contains(ulb)) {
                paths.add(ulb);
            }
        }
    }

    private static void addPathEntries(String path) {
        if (path != null && path.length() > 0) {
            for (String f : path.split("[ :,] *")) {
                Path p = Paths.get(deTilde(f));
                if (!paths.contains(p)) {
                    paths.add(p);
                }
            }
        }
    }

    private static void computePathString() {
        StringBuilder sb = new StringBuilder();
        paths.forEach(p -> {
            if (sb.length() > 5) {
                sb.append(':');
            }
            sb.append(p.toString());
        });
        setDefaultEnv("PATH", sb.toString());
    }

    /**
     * Remove the path from our representation of PATH.
     *
     * @param p path to remove.
     */
    public static void removePath(Path p) {
        if (p != null && paths.remove(p)) {
            computePathString();
        }
    }

    /**
     * Add path to PATH at the head.
     *
     * @param p path to be added.
     */
    public static void addFirstPath(Path p) {
        if (p == null || paths.contains(p)) {
            return;
        }
        paths.addFirst(p);
        computePathString();
    }

    /**
     * Set working directory to the given file.
     *
     * @param f directory to switch into.
     * @return this.
     */
    public Exec cd(File f) {
        if (f != null) {
            dir = f;
        }
        return this;
    }

    public Exec cd(String d) {
        return cd(dir.toPath().toAbsolutePath().resolve(Paths.get(d)).toAbsolutePath().toFile());
    }

    /**
     * Get the working directory which is configured for the Exec.
     *
     * @return current working directory.
     */
    public File cwd() {
        return dir;
    }

    /**
     * Set the command to execute.
     * @param c a command.
     * @return this.
     */
    public Exec withExec(String... c) {
        cmds = c;
        return this;
    }

    /**
     * Decorate a command so that it executes in a default shell.
     *
     * @param command a command to execute.
     * @return this.
     */
    public Exec withShell(String... command) {
        withShell();
        return withExec(command);
    }

    /**
     * Execute the command in a default shell.
     *
     * @return this.
     */
    public Exec withShell() {
        shellDecorator = Platform.getInstance().getShellDecorator();
        return this;
    }

    /**
     * Execute the command using the specified shell.
     *
     * @param shell the shell to use.
     * @return this.
     */
    public Exec usingShell(String shell) {
        shellDecorator = Platform.getInstance().getShellDecorator().withShell(shell);
        return this;
    }

    /**
     * Execute the command with the specified user.
     *
     * @param user a user name or identifier.
     * @return this.
     */
    public Exec withUser(String user) {
        if (userDecorator == null) {
            userDecorator = Platform.getInstance().getUserDecorator();
        }
        userDecorator.withUser(user);
        return this;
    }

    /**
     * Execute the command with the specified group.
     *
     * @param group a group name or identifier.
     * @return this.
     */
    public Exec withGroup(String group) {
        if (userDecorator == null) {
            userDecorator = Platform.getInstance().getUserDecorator();
        }
        userDecorator.withGroup(group);
        return this;
    }

    /**
     * Set the command to run with a given timeout.
     *
     * @param t timeout.
     * @param u units.
     * @return this.
     */
    public Exec withTimeout(long t, TimeUnit u) {
        timeout = t;
        timeunit = u;
        return this;
    }

    @SuppressWarnings("PMD.SystemPrintln")
    public Exec withDumpOut() {
        return withOut(l -> System.out.println("stderr: " + l)).withErr(l -> System.out.println("stdout: " + l));
    }

    public Exec withOut(Consumer<CharSequence> o) {
        stdout = o;
        return this;
    }

    public Exec withErr(Consumer<CharSequence> o) {
        stderr = o;
        return this;
    }

    /**
     * Get the command to execute. This will be decorated if shell and user/group have been provided.
     *
     * @return the command.
     */
    public String[] getCommand() {
        String[] decorated = cmds;
        if (shellDecorator != null) {
            decorated = shellDecorator.decorate(decorated);
        }
        if (userDecorator != null) {
            decorated = userDecorator.decorate(decorated);
        }
        decorated = Platform.getInstance().finalDecorateCommand(decorated);
        return decorated;
    }

    /**
     * Execute a command.
     *
     * @returns the process exit code if it is not a background process.
     * @throws InterruptedException if the command is interrupted while running.
     * @throws IOException if an error occurs while executing.
     */
    @SuppressWarnings("PMD.AvoidRethrowingException")
    public Optional<Integer> exec() throws InterruptedException, IOException {
        // Don't run anything if the current thread is currently interrupted
        if (Thread.currentThread().isInterrupted()) {
            logger.atWarn().kv("command", this).log("Refusing to execute because the active thread is interrupted");
            throw new InterruptedException();
        }
        final String[] command = getCommand();
        logger.atTrace().kv("command", (Supplier<String>) () -> String.join(" ", command)).log();
        process = Runtime.getRuntime().exec(command, environment, dir);
        stderrc = new Copier(process.getErrorStream(), stderr);
        stdoutc = new Copier(process.getInputStream(), stdout);
        stderrc.start();
        stdoutc.start();
        if (whenDone == null) {
            try {
                if (timeout < 0) {
                    process.waitFor();
                } else {
                    if (!process.waitFor(timeout, timeunit)) {
                        (stderr == null ? stdout : stderr).accept("\n[TIMEOUT]\n");
                        process.destroy();
                    }
                }
            } catch (InterruptedException ie) {
                // We just got interrupted by something like the cancel(true) in setBackingTask
                // Give the process a touch more time to exit cleanly
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    (stderr == null ? stdout : stderr).accept("\n[TIMEOUT after InterruptedException]\n");
                    process.destroyForcibly();
                }
                throw ie;
            }
            stderrc.join(5000);
            stdoutc.join(5000);
            return Optional.of(process.exitValue());
        }
        return Optional.empty();
    }

    /**
     * Get the stdout and stderr output as a string.
     *
     * @return String of output.
     * @throws InterruptedException if thread is interrupted while executing
     * @throws IOException          if execution of the process fails to start
     */
    public String execAndGetStringOutput() throws InterruptedException, IOException {
        StringBuilder sb = new StringBuilder();
        Consumer<CharSequence> f = sb::append;
        withOut(f).withErr(f).exec();
        return sb.toString().trim();
    }

    public void background(IntConsumer cb) throws InterruptedException, IOException {
        whenDone = cb;
        exec();
    }

    @SuppressWarnings("PMD.NullAssignment")
    void setClosed() {
        if (!isClosed.get()) {
            final IntConsumer wd = whenDone;
            final int exit = process == null ? -1 : process.exitValue();
            isClosed.set(true);
            if (wd != null) {
                wd.accept(exit);
            }
        }
    }

    public boolean isRunning() {
        return process == null ? !isClosed.get() : process.isAlive();
    }

    /**
     * Get associated process instance representing underlying OS process.
     *
     * @return process object.
     */
    public Process getProcess() {
        return process;
    }

    @Override
    public synchronized void close() throws IOException {
        if (isClosed.get()) {
            return;
        }
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }

        Platform platformInstance = Platform.getInstance();

        Set<Integer> pids = Collections.emptySet();
        try {
            pids = platformInstance.killProcessAndChildren(p, false, pids, userDecorator);
            // TODO: [P41214162] configurable timeout
            // Wait for it to die, but ignore the outcome and just forcefully kill it and all its
            // children anyway. This way, any misbehaving children or grandchildren will be killed
            // whether or not the parent behaved appropriately.

            // Wait up to 5 seconds for each child process to stop
            List<PidProcess> pidProcesses = pids.stream().map(Processes::newPidProcess).collect(Collectors.toList());
            for (PidProcess pp : pidProcesses) {
                pp.waitFor(5, TimeUnit.SECONDS);
            }
            if (pidProcesses.stream().anyMatch(pidProcess -> {
                try {
                    return pidProcess.isAlive();
                } catch (IOException ignored) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return false;
            })) {
                logger.atWarn()
                        .log("Command {} did not respond to interruption within timeout. Going to kill it now", this);
            }
            platformInstance.killProcessAndChildren(p, true, pids, userDecorator);
            if (!p.waitFor(5, TimeUnit.SECONDS) && !isClosed.get()) {
                throw new IOException("Could not stop " + this);
            }
        } catch (InterruptedException e) {
            // If we're interrupted make sure to kill the process before returning
            try {
                platformInstance.killProcessAndChildren(p, true, pids, userDecorator);
            } catch (InterruptedException ignore) {
            }
        }
    }

    @Override
    public String toString() {
        return Utils.deepToString(cmds, 90).toString();
    }

    /**
     * Sends the lines of an InputStream to a consumer in the background.
     */
    private class Copier extends Thread {
        private final Consumer<CharSequence> out;
        private final InputStream in;
        @Getter
        private int nlines = 0;

        Copier(InputStream i, Consumer<CharSequence> s) {
            super("Copier");
            in = i;
            out = s;
            // Set as a daemon thread so that it dies when the main thread exits
            setDaemon(true);
            if (whenDone != null) {
                if (numberOfCopiers == null) {
                    numberOfCopiers = new AtomicInteger(1);
                } else {
                    numberOfCopiers.incrementAndGet();
                }
            }
        }

        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 200)) {
                StringBuilder sb = new StringBuilder();
                while (true) {
                    int c;
                    for (c = br.read(); c >= 0 && c != '\n'; c = br.read()) {
                        sb.append((char) c);
                    }
                    if (c >= 0) {
                        sb.append('\n');
                        nlines++;
                    }
                    if (out != null && sb.length() > 0) {
                        out.accept(sb);
                    }
                    sb.setLength(0);
                    if (c < 0) {
                        break;
                    }
                }
            } catch (Throwable ignore) {
                // nothing that can go wrong here worries us, they're
                // all EOFs
            }
            if (whenDone != null && numberOfCopiers.decrementAndGet() <= 0) {
                try {
                    process.waitFor();
                    setClosed();
                } catch (InterruptedException ignore) {
                    // Ignore as this thread is done running anyway and will exit
                }
            }
        }
    }
}
