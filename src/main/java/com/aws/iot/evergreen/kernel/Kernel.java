/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.*;
import com.aws.iot.evergreen.dependency.*;
import com.aws.iot.evergreen.util.*;

import static com.aws.iot.evergreen.util.Utils.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import static com.fasterxml.jackson.jr.ob.JSON.Feature.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.prefs.*;

/** Evergreen-kernel */
public class Kernel extends Configuration /*implements Runnable*/ {
    private String mainServiceName = "main";
    private boolean installOnly = false;
    private boolean broken = false;
    boolean forReal = true;
    boolean haveRead = false;
    private final Map<String,Class> serviceImplementors = new HashMap<>();
    public static void main(String[] args) {
        Kernel kernel = new Kernel().parseArgs(args).launch();
    }
    @SuppressWarnings("LeakingThisInConstructor")
    public Kernel() {
        super(new Context());
        context.put(Configuration.class, this);
        context.put(Kernel.class, this);
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
    public Kernel parseArgs(String... args) {
        Preferences prefs = Preferences.userNodeForPackage(this.getClass());
        this.args = args;
        Topic root = lookup("system","rootpath")
            .dflt(deTilde(prefs.get("rootpath", "~/.evergreen")))
            .subscribe((w, n) -> {
                rootPath = Paths.get(Coerce.toString(n));
                configPath = Paths.get(deTilde(configPathName));
                Exec.removePath(clitoolPath);
                clitoolPath = Paths.get(deTilde(clitoolPathName));
                Exec.addFirstPath(clitoolPath);
                workPath = Paths.get(deTilde(workPathName));
                Exec.setDefaultEnv("HOME", workPath.toString());
                if (w != WhatHappened.initialized) {
                    ensureCreated(configPath);
                    ensureCreated(clitoolPath);
                    ensureCreated(rootPath);
                    ensureCreated(workPath);
                }
            });
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
                    lookup("system","logfile").setValue(getArg());
                    break;
                case "-root":
                case "-r": {
                    String r = deTilde(getArg());
                    if (Utils.isEmpty(r) || !ensureCreated(Paths.get(r))) {
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
        context.get(EZTemplates.class).addEvaluator(expr->{
            Object value;
            switch(expr) {
                case "root":   value = rootPath; break;
                case "work":   value = workPath; break;
                case "bin":    value = clitoolPath; break;
                case "config": value = configPath; break;
                default:
                    if((value = System.getProperty(expr)) == null
                            && (value = System.getenv(expr)) == null)
                        value = find(splitPath(expr));
            }
            return value;
        });
        return this;
    }
    public Kernel launch() {
        System.out.println("root path = " + rootPath + "\n\t" + configPath);
        installCliTool(this.getClass().getClassLoader().getResource("evergreen-launch"));
        Queue<String> autostart = new LinkedList<>();
        if (!ensureCreated(configPath) || !ensureCreated(rootPath)
                || !ensureCreated(workPath) || !ensureCreated(clitoolPath))
            broken = true;
        Exec.setDefaultEnv("EVERGREEN_HOME", rootPath.toString());
        try {
            EZPlugins pim = context.get(EZPlugins.class);
            pim.setCacheDirectory(rootPath.resolve("plugins"));
            pim.annotated(ImplementsService.class, cl->{
                if(!EvergreenService.class.isAssignableFrom(cl)) {
                    System.err.println(cl+" needs to be a subclass of EvergreenService in order to use ImplementsService");
                    return;
                }
                ImplementsService is = cl.getAnnotation(ImplementsService.class);
                if(is.autostart()) autostart.add(is.name());
                serviceImplementors.put(is.name(),cl);
                System.out.println("Found Plugin: "+cl.getSimpleName());
            });
            pim.loadCache();
            if(!serviceImplementors.isEmpty())
                context.put("service-implementors", serviceImplementors);
            System.out.println("serviceImplementors: "+deepToString(serviceImplementors));
        } catch(Throwable t) {
            System.err.println("Error launching plugins: "+t);
        }
        Path transactionLogPath = configPath.resolve("config.tlog"); //Paths.get(deTilde("~root/config/config.tlog"));
        Path configurationFile = configPath.resolve("config.yaml"); //Paths.get(deTilde("~root/config/config.yaml"));
        if (!broken)
            try {
                if (haveRead) {
                    // new config file came in from the outside
                    try ( CommitableWriter out = CommitableWriter.abandonOnClose(configurationFile)) {
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
//                if(size()<=1) {
//                    read(Kernel.class.getResource("default.yaml"));
//                }
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
            EvergreenService main = getMain(); // Trigger boot  (!?!?)
            autostart.forEach(s->main.addDependency(s, State.AwaitingStartup));
        } catch (Throwable ex) {
            log.error("***BOOT FAILED, SWITCHING TO FALLBACKMAIN*** ",ex);
            mainServiceName = "fallbackMain";
            try {
                getMain(); // trigger fallback boot
            } catch(Throwable t) {
                log.error("***FALLBACK BOOT FAILED, ABANDON ALL HOPE*** ",t);
            }
        }
        try {
            installEverything();
            if(!installOnly)
                startEverything();
        } catch (Throwable ex) {
            context.get(Log.class).error("install", ex);
        }
        return this;
    }
    public void setLogWatcher(Consumer<Log.Entry> lw) {
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
//    @Override
//    public void run() {
//        if (broken)
//            return;
//        System.out.println("Running...");
//    }
    private EvergreenService mainService;
    public EvergreenService getMain() throws Throwable {
        EvergreenService m = mainService;
        if(m == null) m = mainService = (EvergreenService) EvergreenService.locate(context, mainServiceName);
        return m;
    }
    public void installCliTool(URL resource) {
        try {
            String dp = resource.getPath();
            int sl = dp.lastIndexOf('/');
            if(sl>=0) dp = dp.substring(sl+1);
            Path dest = clitoolPath.resolve(dp);
            context.get(EZTemplates.class).rewrite(resource, dest);
            Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("r-xr-x---"));
        } catch(Throwable t) {
            context.get(Log.class).error("installCliTool",t);
        }
    }
    private Collection<EvergreenService> cachedOD = null;
    public void clearODcache() { cachedOD = null; }
    public synchronized Collection<EvergreenService> orderedDependencies() {
        if(cachedOD != null) return cachedOD;
        try {
            final HashSet<EvergreenService> pending = new LinkedHashSet<>();
            getMain().addDependencies(pending);
            final HashSet<EvergreenService> ready = new LinkedHashSet<>();
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
            return cachedOD = ready;
        } catch (Throwable ex) {
            context.get(Log.class).error(ex);
            return Collections.EMPTY_LIST;
        }
    }
    public void installEverything() throws Throwable {
        if (broken)
            return;
        Log log = context.get(Log.class);
        try {
            CommitableWriter out = CommitableWriter.commitOnClose(configPath.resolve("effectiveConfig.evg"));
            try {
                writeConfig(out);  // this is all made messy because writeConfig closes it's output stream
                out.commit();
            } catch(Throwable t) { 
                log.error("Failed to write effective config",t);
                out.abandon();
            }
        } catch(Throwable t) {
            log.error("Failed to write effective config",t);
        }
        log.significant("Installing software", getMain());
        orderedDependencies().forEach(l -> {
            log.significant("Starting to install", l);
            l.setState(State.Installing);
        });
    }
    public void startEverything() throws Throwable {
        if (broken)
            return;
        Log log = context.get(Log.class);
        log.significant("Installing software", getMain());
        orderedDependencies().forEach(l -> {
            log.significant("Starting to install", l);
            l.setState(State.AwaitingStartup);
        });
    }
    public void dump() {
        orderedDependencies().forEach(l -> {
            System.out.println(l.getName()+": "+l.getState());
            if(l.getState().preceeds(State.Running)) {
                l.forAllDependencies(d->System.out.println("    "+d.getName()+": "+d.getState()));
            }
        });
    }
    public void writeConfig(Writer w) {
        Map<String, Object> h = new LinkedHashMap<>();
        orderedDependencies().forEach(l -> {
            if (l instanceof EvergreenService) {
                EvergreenService s = (EvergreenService) l;
                h.put(s.getName(), s.config.toPOJO());
            }
        });
        try {
            JSON.std.with(new YAMLFactory())
                    .write(h, w);
        } catch (IOException ex) {
            ex.printStackTrace(System.out);
        }
    }
    public void shutdown() {
        if (broken)
            return;
        Log log = context.get(Log.class);
        try {
            log.significant("Installing software", getMain());
            EvergreenService[] d = orderedDependencies().toArray(new EvergreenService[0]);
            for (int i = d.length; --i >= 0;) // shutdown in reverse order
                if (d[i].inState(State.Running))
                    try {
                        d[i].close();
                    } catch (Throwable t) {
                        log.error(d[i], "Failed to shutdown", t);
                    }
        } catch (Throwable ex) {
            log.error("Shutdown hook failure", ex);
        }

    }

    
    public Kernel print(OutputStream out) {
        return print(new BufferedWriter(new OutputStreamWriter(out)));
    }
    public Kernel print(Writer out) {
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
        if (clitoolPath != null && s.startsWith("~bin/"))
            s = clitoolPath.resolve(s.substring(5)).toString();
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
    public Path clitoolPath;
    public Path workPath;
    public String configPathName = "~root/config";
    public String clitoolPathName = "~root/bin";
    public String workPathName = "~root/work";
    private String[] args;
    private String arg;
    private static final String done = new String(" missing ".toCharArray()); // unique marker
    private int argpos = 0;
    private String getArg() {
        return arg = args == null || argpos >= args.length ? done : args[argpos++];
    }
    private final Deque<String> serviceServerURLlist = new LinkedList<>();
    public Collection<String> getServiceServerURLlist() { return serviceServerURLlist; }
    public void addServiceServerURL(Object url) {
        if(url!=null) {
            String u = url.toString();
            if(!u.endsWith("/")) u += "/";
            if(!serviceServerURLlist.contains(u))
            serviceServerURLlist.addFirst(u);
        }
    }
    {
        addServiceServerURL("https://raw.githubusercontent.com/JamesGosling/SailingForecast/master/docs/evg/");  // Ignore this hack
        addServiceServerURL(EvergreenService.class.getResource("/config"));
    };
}
