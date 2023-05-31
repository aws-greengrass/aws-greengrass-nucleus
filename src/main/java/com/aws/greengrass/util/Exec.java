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

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
 * Platform.getInstance().createNewProcessRunner()
 * .withShell("tail -F /var/log/system.log")
 * .withOut(str->{
 * if(str.toString().contains("wifi"))
 * System.out.println("Yahoo!");
 * })
 * .background(exc -> System.out.println("exit "+exc));
 * </pre>
 */
public abstract class Exec implements Closeable {
    private static final char PATH_SEP = File.pathSeparatorChar;
    public static final String PATH_ENVVAR = "PATH";
    private static final Logger staticLogger = LogManager.getLogger(Exec.class);
    protected Logger logger = staticLogger;
    private static final Consumer<CharSequence> NOP = s -> {
    };

    // default directory relative paths are resolved against (i.e. current working directory)
    private static final File userdir = new File(System.getProperty("user.dir"));

    protected static final List<Path> paths = Collections.synchronizedList(new ArrayList<>());
    protected static final Map<String, String> defaultEnvironment = new ConcurrentHashMap<>();
    protected Map<String, String> environment;

    static {
        addPathEntries(System.getenv(PATH_ENVVAR));
        computeDefaultPathString();
        defaultEnvironment.put("JAVA_HOME", System.getProperty("java.home"));
        defaultEnvironment.put("HOME", System.getProperty("user.home"));
    }

    protected final AtomicBoolean isClosed = new AtomicBoolean(false);
    protected Process process;
    private IntConsumer whenDone;
    private Consumer<CharSequence> stdout = NOP;
    private Consumer<CharSequence> stderr = NOP;
    private AtomicInteger numberOfCopiers;
    protected String[] cmds;

    protected ShellDecorator shellDecorator;
    protected UserDecorator userDecorator;

    protected File dir = userdir;
    private long timeout = -1;
    private TimeUnit timeunit = TimeUnit.SECONDS;
    private Copier stderrc;
    private Copier stdoutc;
    protected Duration gracefulShutdownTimeout = Duration.ofSeconds(5);

    public static void setDefaultEnv(String key, String value) {
        defaultEnvironment.put(key, value);
    }

    public Exec setenv(String key, CharSequence value) {
        environment.put(key, value instanceof String ? (String) value : Coerce.toString(value));
        return this;
    }

    public Exec logger(Logger logger) {
        this.logger = logger;
        return this;
    }

    // TODO Cleanup convenient methods. These are more than necessary
    public String cmd(String... command) throws InterruptedException, IOException {
        return withExec(command).execAndGetStringOutput();
    }

    public String sh(String command) throws InterruptedException, IOException {
        return sh((File) null, command);
    }

    public String sh(File dir, String command) throws InterruptedException, IOException {
        return cd(dir).withShell(command).execAndGetStringOutput();
    }

    public String sh(Path dir, String command) throws InterruptedException, IOException {
        return sh(dir.toFile(), command);
    }

    public boolean successful(boolean ignoreStderr, String command) throws InterruptedException, IOException {
        return withShell(command).successful(ignoreStderr);
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
    public abstract Path which(String fn);  // mirrors shell command

    protected static String deTilde(String s) {
        if (s.startsWith("~/")) {
            s = Utils.HOME_PATH.resolve(s.substring(2)).toString();
        }
        return s;
    }

    protected static void addPathEntries(String path) {
        if (path != null && path.length() > 0) {
            for (String f : path.split("[" + PATH_SEP + ",] *")) {
                Path p = Paths.get(deTilde(f));
                if (!paths.contains(p)) {
                    paths.add(p);
                }
            }
        }
    }

    protected static void computeDefaultPathString() {
        setDefaultEnv(PATH_ENVVAR, paths.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
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
    public abstract String[] getCommand();

    /**
     * Execute a command.
     *
     * @return the process exit code if it is not a background process.
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
        process = createProcess();
        logger.debug("Created process with pid {}", getPid());

        // By default, do not close stdin.
        if ("true".equalsIgnoreCase(System.getProperty("gg.closeStdIn", "false"))) {
            // Close stdin, no one can write anything to stdin.
            process.getOutputStream().close();
        }

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
     * Create and start the child process in platform-specific ways.
     *
     * @return child process
     * @throws IOException if IO error occurs
     */
    protected abstract Process createProcess() throws IOException;

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

    /**
     * Get the process ID of the underlying process.
     *
     * @return the process PID.
     */
    public abstract int getPid();

    @Override
    public abstract void close() throws IOException;

    @Override
    public String toString() {
        return Utils.deepToString(cmds, 90).toString();
    }

    /**
     * Sends the lines of an InputStream to a consumer in the background.
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
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
                boolean cr = false;
                while (true) {
                    int c;
                    for (c = br.read(); c >= 0 && c != '\n' && c != '\r'; c = br.read()) {
                        sb.append((char) c);
                        cr = false;
                    }

                    // Append a newline to our builder if we get \n or if we are seeing \r for the first time.
                    // This prevents \r\n from causing 2 lines to be logged.
                    // If cr is true and we see another \r, we will append a newline (\r\r is 2 lines).
                    if (c >= 0 && !cr || c == '\r') {
                        // Split on cr too, this protects us from having crazy long lines from a console application
                        // which is using \r to rewrite the last line, ex updating download status.
                        if (c == '\r') {
                            cr = true;
                        }
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
