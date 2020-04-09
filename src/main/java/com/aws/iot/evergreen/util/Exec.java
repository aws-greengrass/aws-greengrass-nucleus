/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
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
@SuppressWarnings({"checkstyle:emptycatchblock", "PMD.AvoidCatchingThrowable"})
public final class Exec implements Closeable {
    public static final boolean isWindows = System.getProperty("os.name").toLowerCase().contains("wind");
    public static final String EvergreenUid = Utils.generateRandomString(16).toUpperCase();
    private static final Consumer<CharSequence> NOP = s -> {
    };
    private static final File userdir = new File(System.getProperty("user.dir"));
    private static final File homedir = new File(System.getProperty("user.home"));

    private static final ConcurrentLinkedDeque<Path> paths = new ConcurrentLinkedDeque<>();
    private static String[] defaultEnvironment = {"PATH=" + System.getenv("PATH"), "SHELL=" + System.getenv("SHELL"),
            "JAVA_HOME=" + System.getProperty("java.home"), "USER=" + System.getProperty("user.name"),
            "HOME=" + homedir, "USERHOME=" + homedir, "EVERGREEN_UID=" + EvergreenUid, "PWD=" + userdir,};

    static {
        addPathEntries(System.getenv("PATH"));
        try {
            // This bit is gross: under some circumstances (like IDEs launched from the
            // macos Dock) the PATH environment variable doesn't match the path one expects
            // after the .profile script is executed.  Fire up a login shell, then grab it's
            // path variable, but without using Exec shorthands to avoid initialization
            // order paradoxes.
            Process hack = Runtime.getRuntime()
                    .exec(new String[]{"bash", "-c", "echo 'echo $PATH'|bash --login|egrep':[^ ]'"});
            StringBuilder path = new StringBuilder();

            Thread bg = new Thread(() -> {
                try (InputStream in = hack.getInputStream()) {
                    for (int c = in.read(); c >= 0; c = in.read()) {
                        path.append((char) c);
                    }
                } catch (Throwable ignored) {
                }
            });
            bg.start();
            // TODO: configurable timeout?
            bg.join(2000);
            addPathEntries(path.toString().trim());
            // Ensure some level of sanity
            ensurePresent("/usr/local/bin", "/bin", "/usr/bin", "/sbin", "/usr/sbin", System.getProperty("java.home"));
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
        }
        computePathString();
    }

    private String[] environment = defaultEnvironment;
    private String[] cmds;
    private File dir = userdir;
    Process process;
    private long timeout = -1;
    private TimeUnit timeunit = TimeUnit.SECONDS;
    IntConsumer whenDone;
    Consumer<CharSequence> stdout = NOP;
    Consumer<CharSequence> stderr = NOP;
    private Copier stderrc;
    private Copier stdoutc;
    private boolean closed = false;
    AtomicInteger numberOfCopiers;

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

    private static void appendStackTrace(Throwable ex, Consumer<CharSequence> a) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Utils.getUltimateCause(ex).printStackTrace(pw);
        pw.flush();
        a.accept(sw.toString());
    }

    public static String cmd(String... command) throws InterruptedException {
        return new Exec().withExec(command).execAndGetStringOutput();
    }

    public static String sh(String command) throws InterruptedException {
        return sh((File) null, command);
    }

    public static String sh(File dir, String command) throws InterruptedException {
        return new Exec().cd(dir).withShell(command).execAndGetStringOutput();
    }

    public static String sh(Path dir, String command) throws InterruptedException {
        return sh(dir.toFile(), command);
    }

    public static boolean successful(boolean ignoreStderr, String command) throws InterruptedException {
        return new Exec().withShell(command).successful(ignoreStderr);
    }

    public boolean successful(boolean ignoreStderr) throws InterruptedException {
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
        fn = deTilde(fn);
        if (fn.startsWith("/")) {
            // TODO sort out windows filename issues, if we ever care
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
        //        System.out.println("Adding to PATH: "+path);
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
        return cd(new File(dir, d));
    }

    public Exec cd() {
        return cd(homedir);
    }

    public Exec withExec(String... c) {
        cmds = c;
        return this;
    }

    public Exec withShell(String s) {
        return withExec("sh", "-c", s);
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

    @SuppressWarnings("PMD.AvoidRethrowingException")
    private void exec() throws InterruptedException {
        try {
            process = Runtime.getRuntime().exec(cmds, environment, dir);
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
                            process.destroyForcibly();
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
            }
        } catch (InterruptedException ex) {
            // The thread was interrupted while we were executing. We rethrow the exception
            // so that the caller knows to be interrupted and stop processing so that the
            // thread can shutdown gracefully
            throw ex;
        } catch (Throwable ex) {
            if (stderr != null) {
                appendStackTrace(ex, stderr);
            }
        }
    }

    /**
     * Get the stdout and stderr output as a string.
     *
     * @return String of output.
     * @throws InterruptedException if thread is interrupted while executing
     */
    public String execAndGetStringOutput() throws InterruptedException {
        StringBuilder sb = new StringBuilder();
        Consumer<CharSequence> f = sb::append;
        withOut(f).withErr(f).exec();
        return sb.toString().trim();
    }

    public void background(IntConsumer cb) throws InterruptedException {
        whenDone = cb;
        exec();
    }

    @SuppressWarnings("PMD.NullAssignment")
    synchronized void setClosed() {
        if (!closed) {
            final IntConsumer wd = whenDone;
            final int exit = process == null ? -1 : process.exitValue();
            closed = true;
            process = null;
            stderrc = null;
            stdoutc = null;
            whenDone = null;
            if (wd != null) {
                wd.accept(exit);
            }
            notifyAll();
        }
    }

    @SuppressFBWarnings(value = "IS2_INCONSISTENT_SYNC", justification = "No need to be sync")
    public boolean isRunning() {
        return !closed;
    }

    /**
     * Wait until the execution has closed.
     *
     * @param timeout wait up to this many milliseconds.
     * @return true if closed successfully
     */
    @SuppressWarnings("checkstyle:emptycatchblock")
    public synchronized boolean waitClosed(int timeout) {
        while (!closed) {
            try {
                wait(timeout);
            } catch (InterruptedException ignored) {
            }
        }
        return closed;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        Process p = process;
        if (p == null) {
            return;
        }
        p.destroy();
        // TODO: configurable timeout?
        if (!waitClosed(2000)) {
            p.destroyForcibly();
            if (!waitClosed(5000)) {
                throw new IOException("Could not stop " + this);
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
            super();
            in = i;
            out = s;
            // Set as a daemon thread so that it dies when the main thread exits
            setDaemon(true);
            setName("Copier");
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
            } catch (Throwable t) {
                // nothing that can go wrong here worries us, they're
                // all EOFs
            }
            if (whenDone != null && numberOfCopiers.decrementAndGet() <= 0) {
                try {
                    // TODO: configurable timeout?
                    process.waitFor(10, TimeUnit.SECONDS); // be graceful
                    setClosed();
                } catch (Throwable t) {
                    t.printStackTrace(System.out);
                }
            }
        }
    }
}
