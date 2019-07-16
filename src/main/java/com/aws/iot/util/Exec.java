/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.util;

import static com.aws.iot.util.Utils.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Vaguely like ProcessBuilder, but more flexible:
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
            .background(exc -> {});
    </pre>
 */
public class Exec {
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
    public static final boolean isWindows = System.getProperty("os.name")
            .toLowerCase().contains("wind");
    private static final File userdir = new File(System.getProperty("user.dir"));
    private static final File homedir = new File(System.getProperty("user.home"));
    private static final Path[] execPathArr = computePathArr();
    private static final String execPath = computePathString(execPathArr);
    private static final String[] defaultEnvironment = {
        "PATH=" + execPath,
        "SHELL=" + System.getenv("SHELL"),
        "JAVA_HOME=" + System.getenv("JAVA_HOME"),
        "USER=" + System.getProperty("user.name"),
        "HOME=" + homedir,
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
    public Exec setenv(String key, String value) {
        String[] ne = Arrays.copyOf(environment, environment.length + 1, String[].class);
        ne[ne.length - 1] = key + '=' + value;
        environment = ne;
        return this;
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
                if (!process.waitFor(timeout, timeunit)) {
                    (stderr != null ? stderr : stdout).accept("\n[TIMEOUT]\n");
                    process.destroyForcibly();
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
        int l = sb.length();
        if (l > 0)
            sb.setLength(l - 1); // trim guaranteed trailing newline
        return sb.toString();
    }
    public boolean successful() {
        exec();
        return stderrc.nlines==0 && process.exitValue() == 0;
    }
    public void background(IntConsumer cb) {
        whenDone = cb;
        exec();
    }
    private static void appendStackTrace(Throwable ex, Consumer<CharSequence> a) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        getUltimateCause(ex).printStackTrace(pw);
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
    public static boolean successful(String command) {
        return new Exec().withShell(command).successful();
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
                appendStackTrace(t, out);
            }
            if (whenDone != null && numberOfCopiers.decrementAndGet() <= 0)
                try {
                    process.waitFor(10, TimeUnit.SECONDS);
                    whenDone.accept(process.exitValue());
                } catch (Throwable t) {
                    t.printStackTrace(System.out);
                }
        }
    }
    public static Path which(String fn) {  // mirrors shell command
        fn = deTilde(fn);
        if(fn.startsWith("/")) {
            // TODO sorts out windows filename issues, if we ever care
            Path f = Path.of(fn);
            return Files.isExecutable(f) ? f : null;
        }
        for(Path d:execPathArr) {
            Path f = d.resolve(fn);
            if(Files.isExecutable(f))
                return f;
        }
        return null;
    }
    private static String deTilde(String s) {
        if(s.startsWith("~/"))
            s = homePath.resolve(s.substring(2)).toString();
        return s;
    }
    private static Path[] computePathArr() {
        LinkedHashSet<Path> p = new LinkedHashSet<>();
        addPathEntries(p, System.getenv("PATH"));
        
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
            bg.join(500);
//            hack.waitFor();
            addPathEntries(p,path.toString().trim());
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
        }
//        System.out.println("Search path: "+deepToString(p));
        return p.toArray(n->new Path[n]);
    }
    private static void addPathEntries(Set<Path> s, String path) {
//        System.out.println("Adding to PATH: "+path);
        if(path!=null && path.length()>0)
        for(String f:path.split("[ :,] *"))
            s.add(Path.of(deTilde(f)));
    }
    private static String computePathString(Path[] execPathArr) {
        StringBuilder sb = new StringBuilder();
        for(Path p:execPathArr) if(p!=null) {
            if(sb.length()>0) sb.append(':');
            sb.append(p.toString());
        }
        return sb.toString();
    }
}
