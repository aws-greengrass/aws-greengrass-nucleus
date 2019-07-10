/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.gg2k;

import com.aws.iot.config.*;
import com.aws.iot.dependency.*;
import com.aws.iot.dependency.Lifecycle.State;
import static com.aws.iot.dependency.Lifecycle.State.*;
import com.aws.iot.util.*;
import static com.aws.iot.util.Utils.*;
import com.fasterxml.jackson.jr.ob.*;
import static com.fasterxml.jackson.jr.ob.JSON.Feature.*;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.prefs.*;
import java.util.regex.*;

/** GreenGrass-v2-kernel */
public class GG2K extends Configuration implements Runnable {
    public final Context context = new Context();
    private String mainServiceName = "main";
    private boolean installOnly = false;
    private boolean broken = false;
    public static void main(String[] args) {
        GG2K ggc = new GG2K().parseArgs(args);
    }
    @SuppressWarnings("LeakingThisInConstructor")
    public GG2K() {
        context.put(Configuration.class, this);
        context.put(GG2K.class, this);
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(2);
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, ses);
        context.put(ExecutorService.class, ses);
        context.put(ThreadPoolExecutor.class, ses);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                shutdown();
            }
        });

    }
    public GG2K parseArgs(String... args) {
        Preferences prefs = Preferences.userNodeForPackage(this.getClass());
        this.args = args;
        boolean forReal = true;
        Topic root = lookup("system.rootpath");
        root.subscribe((w, n, o) -> {
            rootPath = Path.of(n.toString());
            configPath = Path.of(deTilde(configPathName));
            workPath = Path.of(deTilde(workPathName));
            if (w != WhatHappened.initialized) {
                ensureCreated(configPath);
                ensureCreated(rootPath);
                ensureCreated(workPath);
            }
        });
        root.setValue(0, deTilde(prefs.get("rootpath", "~/gg2root")));
        boolean haveRead = false;
        while (getArg() != (Object) done)
            switch (arg) {
                case "-install":
                    installOnly = true;
                    break;
                case "-dryrun":
                    forReal = false;
                    break;
                case "-forreal":
                case "-forReal":
                    forReal = true;
                    break;
                case "-config":
                case "-i":
                    try {
                        read(deTilde(getArg()));
                        haveRead = true;
                    } catch (Throwable ex) {
                        broken = true;
                        System.out.println("Can't read " + arg + ": " + ex.getLocalizedMessage());
                    }
                    break;
                case "-log":
                case "-l":
                    lookup("system.logfile").setValue(getArg());
                    break;
                case "-root":
                case "-r": {
                    String r = deTilde(getArg());
                    if (isEmpty(r) || !ensureCreated(Path.of(r))) {
                        System.err.println(r + ": not a valid root directory");
                        broken = true;
                        break;
                    }
                    root.setValue(r);
                    prefs.put("rootpath", String.valueOf(root.getOnce())); // make root setting sticky
                }
                break;
                case "-main":
                    mainServiceName = getArg();
                    break;
                case "-print":
                    print(System.out);
                    break;
                default:
                    System.err.println("Undefined command line argument: " + arg);
                    broken = true;
                    break;
            }
        System.out.println("root path = " + rootPath + "\n\t" + configPath);
        if (!ensureCreated(configPath) || !ensureCreated(rootPath) || !ensureCreated(workPath))
            broken = true;
        Path transactionLogPath = Path.of(deTilde("~root/config/config.tlog"));
        Path configurationFile = Path.of(deTilde("~root/config/config.yaml"));
        if (!broken)
            try {
                if (haveRead) {
                    // new config file came in from the outside
                    try ( CommitableWriter out = CommitableWriter.of(configurationFile)) {
                        print(uncloseable(out));
                        out.commit();
                    }
                    Files.deleteIfExists(transactionLogPath);
                } else {
                    if (Files.exists(configurationFile))
                        read(configurationFile);
                    if (Files.exists(transactionLogPath))
                        read(transactionLogPath);
                }
                ConfigurationWriter.logTransactionsTo(this, transactionLogPath);
            } catch (Throwable ioe) {
                ioe.printStackTrace(System.err);
                System.err.println("Couldn't read config: " + getUltimateMessage(ioe));
                broken = true;
                return this;
            }
//        if (broken)
//            System.exit(126);
        final Log log = context.get(Log.class);
        log.addWatcher(logWatcher);
        if (!forReal)
            context.put(ShellRunner.class,
                    context.get(ShellRunner.Dryrun.class));
        try {
            getMain(); // Trigger boot  (!?!?)
        } catch (Throwable ex) {
            log.error("***BOOT FAILED, SWITCHING TO FALLBACKMAIN*** ",ex);
            mainServiceName = "fallbackMain";
            try {
                getMain(); // trigger fallback boot
            } catch(Throwable t) {
                log.error("***FALLBACK BOOT FAILED, ABANDON ALL HOPE*** ",t);
            }
        }
        if (installOnly)
            try {
                justInstall();
            } catch (Throwable ex) {
                context.get(Log.class).error("install", ex);
            }
        return this;
    }
    public void setWatcher(Consumer<Log.Entry> lw) {
        logWatcher = lw;
    }
    private Consumer<Log.Entry> logWatcher = null;
    private static boolean ensureCreated(Path p) {
        try {
            Files.createDirectories(p,
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
            return true;
        } catch (IOException ex) {
            System.err.println("Could not create " + p);
            ex.printStackTrace(System.err);
            return false;
        }
    }
    public static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }
    @Override
    public void run() {
        if (broken)
            return;
        System.out.println("Running...");
    }
    private GGService mainService;
    public GGService getMain() throws Throwable {
        GGService m = mainService;
        if(m == null) m = mainService = (GGService)GGService.locate(context, mainServiceName);
        return m;
    }
//    public Collection<GGService> orderedDependencies() {
//        try {
//            return getMain().orderedDependencies();
//        } catch (Throwable ex) {
//            context.get(Log.class).error(ex);
//            return Collections.EMPTY_LIST;
//        }
//    }
    public Collection<GGService> orderedDependencies() {
        try {
            final HashSet<GGService> pending = new LinkedHashSet<>();
            getMain().addDependencies(pending);
            final HashSet<GGService> ready = new LinkedHashSet<>();
            while (!pending.isEmpty()) {
                int sz = pending.size();
                pending.removeIf(l -> {
                    if (l.satisfiedBy(ready)) {
                        ready.add(l);
                        return true;
                    }
                    return false;
                });
                if (sz == pending.size())
                    // didn't find anything to remove, there must be a cycle
                    break;
            }
            return ready;
        } catch (Throwable ex) {
            context.get(Log.class).error(ex);
            return Collections.EMPTY_LIST;
        }
    }
    public void justInstall() throws Throwable {
        if (broken)
            return;
        Log log = context.get(Log.class);
        log.significant("Installing software", getMain());
        orderedDependencies().forEach(l -> {
            log.significant("Starting to install", l);
            l.setState(State.Installed);
        });
    }
    public void shutdown() {
        if (broken)
            return;
        Log log = context.get(Log.class);
        try {
            log.significant("Installing software", getMain());
            Lifecycle[] d = orderedDependencies().toArray(n -> new Lifecycle[n]);
            for (int i = d.length; --i >= 0;) // shutdown in reverse order
                if (d[i].getState() == Running)
                    try {
                        d[i].setState(Shutdown);
                    } catch (Throwable t) {
                        log.error(d[i], "Failed to shutdown", t);
                    }
        } catch (Throwable ex) {
            log.error("Shutdown hook failure", ex);
        }

    }

    public GG2K read(String s) throws IOException {
        System.out.println("Reading " + s);
        return read(isURL.matcher(s).lookingAt()
                ? new URL(s).openStream()
                : new FileInputStream(s),
                extension(s));
    }
    public GG2K read(Path s) throws IOException {
        System.out.println("Reading " + s);
        return read(Files.newBufferedReader(s), extension(s.toString()));
    }
    public GG2K read(InputStream in, String extension) throws IOException {
        return read(new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8"))),
                extension);
    }
    public GG2K read(Reader in, String extension) throws IOException {
        if (broken)
            return this;
        try {
            switch (extension) {
                case "json":
                    mergeMap(0, (java.util.Map) JSON.std.anyFrom(in));
                    break;
                case "yaml":
                    mergeMap(0, (java.util.Map) JSON.std.with(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory()).anyFrom(in));
                    break;
                case "tlog":
                    ConfigurationReader.read(this, in);
                    break;
                default:
                    throw new IllegalArgumentException("File format '" + extension + "' is not supported.  Use one of: yaml, json or tlog");
            }
        } finally {
            close(in);
        }
        return this;
    }
    public GG2K print(OutputStream out) {
        return print(new BufferedWriter(new OutputStreamWriter(out)));
    }
    public GG2K print(Writer out) {
        try {
            com.fasterxml.jackson.jr.ob.JSON.std
                    .with(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .with(PRETTY_PRINT_OUTPUT)
                    //                    .without(AUTO_CLOSE_TARGET)
                    .write(toPOJO(), out);
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
        }
        return this;
    }
    public String deTilde(String s) {
        if (s.startsWith("~/"))
            s = homePath.resolve(s.substring(2)).toString();
        if (rootPath != null && s.startsWith("~root/"))
            s = rootPath.resolve(s.substring(6)).toString();
        if (configPath != null && s.startsWith("~config/"))
            s = configPath.resolve(s.substring(8)).toString();
        return s;
    }
    public static Writer uncloseable(Writer w) {
        return new FilterWriter(w) {
            @Override
            public void close() {
            }
        };
    }
    public Path rootPath;
    public Path configPath;
    public Path workPath;
    public String configPathName = "~root/config";
    public String workPathName = "~root/work";
    private String[] args;
    private String arg;
    private static final String done = new String(" missing ".toCharArray()); // unique marker
    private int argpos = 0;
    private String getArg() {
        return arg = args == null || argpos >= args.length ? done : args[argpos++];
    }
    private static final Pattern isURL = Pattern.compile("[a-z]*:/");

}
