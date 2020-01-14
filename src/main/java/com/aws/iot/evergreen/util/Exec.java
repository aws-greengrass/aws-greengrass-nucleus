/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Vaguely like ProcessBuilder, but more flexible and lambda-friendly:
    <pre>
    // set wd to current working directory
    String wd = Exec.sh("pwd");

    // run a shell in the background, and print "Yahoo!"
    // when "wifi" appears in the system log
    new Exec().withShell("tail -F /var/log/system.log")
            .withOut(str->{
                if(str.toString().contains("wifi"))
                    System.out.println("Yahoo!");
            })
            .background(exc -> System.out.println("exit "+exc));
    </pre>
 */
public class Exec implements Closeable {
    private String[] environment = defaultEnvironment;
    private String[] cmds;
    private File dir = userdir;
    private Process process;
    private long timeout = 120;
    private TimeUnit timeunit = TimeUnit.SECONDS;
    private IntConsumer whenDone;
    private static final Consumer<CharSequence> NOP = s->{};
    private Consumer<CharSequence> stdout = NOP;
    private Consumer<CharSequence> stderr = NOP;
    private Copier stderrc, stdoutc;
    private boolean closed = false;
    public static final boolean isWindows = System.getProperty("os.name")
            .toLowerCase().contains("wind");
    private static final File userdir = new File(System.getProperty("user.dir"));
    private static final File homedir = new File(System.getProperty("user.home"));
    public static final String EvergreenUid = Utils.generateRandomString(16).toUpperCase();
    private static String[] defaultEnvironment = {
        "PATH=" + System.getenv("PATH"),
        "SHELL=" + System.getenv("SHELL"),
        "JAVA_HOME=" + System.getProperty("java.home"),
        "USER=" + System.getProperty("user.name"),
        "HOME=" + homedir,
        "USERHOME=" + homedir,
        "EVERGREEN_UID="+ EvergreenUid,
        "PWD=" + userdir,};
    public Exec cd(File f) {
        if (f != null)
            dir = f;
        return this;
    }
    public Exec cd(String d) {
        return cd(new File(dir, d));
    }
    public Exec cd() {
        return cd(homedir);
    }
    public Exec setenv(String key, CharSequence value) {
        environment = setenv(environment, key, value, environment==defaultEnvironment);
        return this;
    }
    public static void setDefaultEnv(String key, CharSequence value) {
        defaultEnvironment = setenv(defaultEnvironment, key, value, false);
    }
    private static String[] setenv(String[] env, String key, CharSequence value, boolean forceCopy) {
        int elen = env.length;
        int klen = key.length();
        for(int i = 0; i<elen; i++) {
            String s = env[i];
            if(s.length()>klen && s.charAt(klen)=='=' && s.startsWith(key)) {
                if(forceCopy) env = Arrays.copyOf(env, env.length);
                env[i] = key+'='+value;
                return env;
            }
        }
        String[] ne = Arrays.copyOf(env, elen + 1, String[].class);
        ne[elen] = key + '=' + value;
        return ne;
    }
    public Exec withExec(String... c) {
        cmds = c;
        return this;
    }
    public Exec withShell(String s) {
        return withExec("sh", "-c", s);
    }
    public Exec withTimeout(long t, TimeUnit u) {
        timeout = t;
        timeunit = u;
        return this;
    }
    public Exec withDumpOut() {
        return withOut(l->System.out.println("stderr: "+l))
                .withErr(l->System.out.println("stdout: "+l));
    }
    public Exec withOut(Consumer<CharSequence> o) { stdout = o; return this; }
    public Exec withErr(Consumer<CharSequence> o) { stderr = o; return this; }
    private void exec() {
        try {
            process = Runtime.getRuntime().exec(cmds, environment, dir);
            stderrc = new Copier(process.getErrorStream(), stderr);
            stdoutc = new Copier(process.getInputStream(), stdout);
            stderrc.start();
            stdoutc.start();
            if (whenDone == null) {
                try {
                    if (!process.waitFor(timeout, timeunit)) {
                        (stderr != null ? stderr : stdout).accept("\n[TIMEOUT]\n");
                        process.destroyForcibly();
                    }
                } catch(InterruptedException ie) {
                    // We just got interrupted by something like the cancel(true) in setBackingTask
                    // Give the process a touch more time to exit cleanly
                    if (!process.waitFor(5, TimeUnit.SECONDS)) {
                        (stderr != null ? stderr : stdout).accept("\n[TIMEOUT after InterruptedException]\n");
                        process.destroyForcibly();
                    }
                }
                stderrc.join(5000);
                stdoutc.join(5000);
            }
        } catch (Throwable ex) {
            if (stderr != null)
                appendStackTrace(ex, stderr);
        }
    }
    public String asString() {
        StringBuilder sb = new StringBuilder();
        Consumer<CharSequence> f = s -> sb.append(s);
        withOut(f).withErr(f).exec();
        return sb.toString().trim();
    }
    public boolean successful(boolean ignoreStderr) {
        exec();
        return (ignoreStderr || stderrc.nlines==0) && process.exitValue() == 0;
    }
    public void background(IntConsumer cb) {
        whenDone = cb;
        exec();
    }
    private static void appendStackTrace(Throwable ex, Consumer<CharSequence> a) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        Utils.getUltimateCause(ex).printStackTrace(pw);
        pw.flush();
        a.accept(sw.toString());
    }
    public static String cmd(String... command) {
        return new Exec().withExec(command).asString();
    }
    public static String sh(String command) {
        return sh((File) null, command);
    }
    public static String sh(File dir, String command) {
        return new Exec().cd(dir).withShell(command).asString();
    }
    public static String sh(Path dir, String command) {
        return sh(dir.toFile(), command);
    }
    public static boolean successful(boolean ignoreStderr, String command) {
        return new Exec().withShell(command).successful(ignoreStderr);
    }
    private AtomicInteger numberOfCopiers;

    /** Sends the lines of an InputStream to a consumer in the background */
    private class Copier extends Thread {
        private final Consumer<CharSequence> out;
        private final InputStream in;
        int nlines = 0;
        Copier(InputStream i, Consumer<CharSequence> s) {
            in = i;
            out = s;
//            setDaemon(true);
            setName("Copier");
            if (whenDone != null)
                if (numberOfCopiers == null)
                    numberOfCopiers = new AtomicInteger(1);
                else
                    numberOfCopiers.incrementAndGet();
        }
        @Override
        public void run() {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(in), 200)) {
                StringBuilder sb = new StringBuilder();
                while (true) {
                    int c;
                    while ((c = br.read()) >= 0 && c != '\n')
                        sb.append((char) c);
                    if (c >= 0) {
                        sb.append('\n');
                        nlines++;
                    }
                    if (out != null && sb.length() > 0)
                        out.accept(sb);
                    sb.setLength(0);
                    if (c < 0)
                        break;
                }
            } catch (Throwable t) {
                // nothing that can go wrong here worries us, they're
                // all EOFs
                // appendStackTrace(t, out);
            }
            if (whenDone != null && numberOfCopiers.decrementAndGet() <= 0)
                try {
                    // TODO: configurable timeout?
                    process.waitFor(10, TimeUnit.SECONDS); // be graceful
                    setClosed();
                } catch (Throwable t) {
                    t.printStackTrace(System.out);
                }
        }
    }
    synchronized void setClosed() {
        if(!closed) {
            IntConsumer wd = whenDone;
            int exit = process!=null ? process.exitValue() : -1;
            closed = true;
            process = null;
            stderrc = null;
            stdoutc = null;
            whenDone = null;
            if(wd!=null) wd.accept(exit);
            notifyAll();
        }
    }
    public boolean isRunning() { return !closed; }
    public synchronized boolean waitClosed(int timeout) {
        if(!closed) try {
            wait(timeout);
        } catch(InterruptedException ie){}
        return closed;
    }
    @Override
    public synchronized void close() throws IOException {
        if(!closed) {
            Process p = process;
            if(p!=null) {
                p.destroy();
                // TODO: configurable timeout?
                if(!waitClosed(2000)) {
                    p.destroyForcibly();
                    if(!waitClosed(5000))
                        throw new IOException("Could not stop "+this);
                }
            }
        }
    }
    @Override
    public String toString() {
        return Utils.deepToString(cmds, 90).toString();
    }
    public static Path which(String fn) {  // mirrors shell command
        fn = deTilde(fn);
        if(fn.startsWith("/")) {
            // TODO sort out windows filename issues, if we ever care
            Path f = Paths.get(fn);
            return Files.isExecutable(f) ? f : null;
        }
        for(Path d:paths) {
            Path f = d.resolve(fn);
            if(Files.isExecutable(f))
                return f;
        }
        return null;
    }
    private static String deTilde(String s) {
        if(s.startsWith("~/"))
            s = Utils.homePath.resolve(s.substring(2)).toString();
        return s;
    }
    private static final LinkedList<Path> paths = new LinkedList<>();
    static {
        addPathEntries(System.getenv("PATH"));
        try {
            // This bit is gross: under some circumstances (like IDEs launched from the
            // macos Dock) the PATH environment variable doesn't match the path one expects
            // after the .profile script is executed.  Fire up a login shell, then grab it's
            // path variable, but without using Exec shorthands to avoid initialization
            // order paradoxes.
            Process hack = Runtime.getRuntime().exec(new String[]{"bash", "-c", "echo 'echo $PATH'|bash --login|egrep ':[^ ]'"});
            StringBuilder path = new StringBuilder();
            Thread bg = new Thread() {
                @Override public void run() {
                    try(InputStream in = hack.getInputStream()) {
                        int c;
                        while((c = in.read())>=0)
                            path.append((char)c);
                    } catch(Throwable t) {}
//                    System.out.println("Read from process: "+path);
                }
            };
            bg.start();
            // TODO: configurable timeout?
            bg.join(2000);
            addPathEntries(path.toString().trim());
            // Ensure some level of sanity
            ensurePresent("/usr/local/bin","/bin","/usr/bin", "/sbin", "/usr/sbin",
                    System.getProperty("java.home"));
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
        }
        computePathString();
    }
    private static void ensurePresent(String... fns) {
        for(String fn:fns) {
            Path ulb = Paths.get(fn);
            if(Files.isDirectory(ulb) && !paths.contains(ulb))
                paths.add(ulb);
        }
    }
    private static void addPathEntries(String path) {
//        System.out.println("Adding to PATH: "+path);
        if(path!=null && path.length()>0)
        for(String f:path.split("[ :,] *")) {
            Path p =Paths.get(deTilde(f));
            if(!paths.contains(p)) paths.add(p);
        }
    }
    private static void computePathString() {
        StringBuilder sb = new StringBuilder();
        paths.forEach(p -> {
            if(sb.length()>5) sb.append(':');
            sb.append(p.toString());
        });
        setDefaultEnv("PATH", sb.toString());
    }
    public static void removePath(Path p) {
        if(p!=null && paths.remove(p))
            computePathString();
    }
    public static void addFirstPath(Path p) {
        if(p==null || paths.contains(p)) return;
        paths.addFirst(p);
        computePathString();
    }
}
