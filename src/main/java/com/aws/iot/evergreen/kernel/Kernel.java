/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;
import com.aws.iot.evergreen.deployment.IotJobsHelper;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.ConfigurationWriter;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.WhatHappened;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.EZPlugins;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.CommitableWriter;
import com.aws.iot.evergreen.util.Exec;
import com.aws.iot.evergreen.util.Utils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import static com.aws.iot.evergreen.util.Utils.close;
import static com.aws.iot.evergreen.util.Utils.deepToString;
import static com.aws.iot.evergreen.util.Utils.homePath;

/**
 * Evergreen-kernel.
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "We don't need equality")
public class Kernel extends Configuration /*implements Runnable*/ {
    private static final Logger logger = LogManager.getLogger(Kernel.class);
    private static final String done = new String(" missing ".toCharArray()); // unique marker
    private final Map<String, Class> serviceImplementors = new HashMap<>();
    private final List<String> serviceServerURLlist = new ArrayList<>();
    public Path rootPath;
    public Path configPath;
    public Path clitoolPath;
    public Path workPath;
    public String configPathName = "~root/config";
    public String clitoolPathName = "~root/bin";
    public String workPathName = "~root/work";
    boolean forReal = true;
    boolean haveRead = false;
    private String mainServiceName = "main";
    private boolean broken = false;
    private ConfigurationWriter tlog;
    private EvergreenService mainService;
    private Collection<EvergreenService> cachedOD = null;
    private String[] args;
    private String arg;
    private int argpos = 0;
    private boolean serviceServerURLlistIsPopulated;

    @SuppressWarnings("LeakingThisInConstructor")
    public Kernel() {
        super(new Context());
        context.put(Configuration.class, this);
        context.put(Kernel.class, this);
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(4);
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, ses);
        context.put(ExecutorService.class, ses);
        context.put(ThreadPoolExecutor.class, ses);
        context.put(IotJobsHelper.class, new IotJobsHelper());
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                shutdown();
            }
        });
    }

    public static void main(String[] args) {
        new Kernel().parseArgs(args).launch();
    }

    public static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException ex) {
            return 0;
        }
    }

    public Kernel parseArgs(String... args) {
        Preferences prefs = Preferences.userNodeForPackage(this.getClass());
        this.args = args;
        Topic root =
                lookup("system", "rootpath").dflt(deTilde(prefs.get("rootpath", "~/.evergreen"))).subscribe((w, n) -> {
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
        while (!Objects.equals(getArg(), done)) {
            switch (arg) {
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
                    lookup("log", "file").setValue(getArg());
                    break;
                case "-search":
                case "-s":
                    addServiceSearchURL(getArg());
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
                    writeConfig(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
                    break;
                default:
                    System.err.println("Undefined command line argument: " + arg);
                    broken = true;
                    break;
            }
        }
        context.get(EZTemplates.class).addEvaluator(expr -> {
            Object value;
            switch (expr) {
                case "root":
                    value = rootPath;
                    break;
                case "work":
                    value = workPath;
                    break;
                case "bin":
                    value = clitoolPath;
                    break;
                case "config":
                    value = configPath;
                    break;
                default:
                    if ((value = System.getProperty(expr)) == null && (value = System.getenv(expr)) == null) {
                        value = find(splitPath(expr));
                    }
            }
            return value;
        });
        return this;
    }

    public Kernel launch() {
        System.out.println("root path = " + rootPath + "\n\t" + configPath);
        installCliTool(this.getClass().getClassLoader().getResource("evergreen-launch"));
        Queue<String> autostart = new LinkedList<>();
        if (!ensureCreated(configPath) || !ensureCreated(rootPath) || !ensureCreated(workPath) || !ensureCreated(
                clitoolPath)) {
            broken = true;
        }
        Exec.setDefaultEnv("EVERGREEN_HOME", rootPath.toString());
        try {
            EZPlugins pim = context.get(EZPlugins.class);
            pim.setCacheDirectory(rootPath.resolve("plugins"));
            pim.annotated(ImplementsService.class, cl -> {
                if (!EvergreenService.class.isAssignableFrom(cl)) {
                    System.err.println(
                            cl + " needs to be a subclass of EvergreenService in order to use ImplementsService");
                    return;
                }
                ImplementsService is = cl.getAnnotation(ImplementsService.class);
                if (is.autostart()) {
                    autostart.add(is.name());
                }
                serviceImplementors.put(is.name(), cl);
                System.out.println("Found Plugin: " + cl.getSimpleName());
            });

            pim.loadCache();
            if (!serviceImplementors.isEmpty()) {
                context.put("service-implementors", serviceImplementors);
            }
            System.out.println("serviceImplementors: " + deepToString(serviceImplementors));
        } catch (Throwable t) {
            System.err.println("Error launching plugins: " + t);
        }
        Path transactionLogPath = configPath.resolve("config.tlog"); //Paths.get(deTilde("~root/config/config.tlog"));
        Path configurationFile = configPath.resolve("config.yaml"); //Paths.get(deTilde("~root/config/config.yaml"));
        if (!broken) {
            try {
                if (haveRead) {
                    // new config file came in from the outside
                    writeEffectiveConfig(configurationFile);
                    Files.deleteIfExists(transactionLogPath);
                } else {
                    if (Files.exists(configurationFile)) {
                        read(configurationFile);
                    }
                    if (Files.exists(transactionLogPath)) {
                        read(transactionLogPath);
                    }
                }
                //                if(size()<=1) {
                //                    read(Kernel.class.getResource("default.yaml"));
                //                }
                tlog = ConfigurationWriter.logTransactionsTo(this, transactionLogPath);
                tlog.flushImmediately(true);
            } catch (Throwable ioe) {
                // Too early in the boot to log a message
                logger.atError().setEventType("system-config-error").setCause(ioe).log();
                broken = true;
                return this;
            }
        }
        //        if (broken)
        //            System.exit(126);
        if (!forReal) {
            context.put(ShellRunner.class, context.get(ShellRunner.Dryrun.class));
        }
        try {
            EvergreenService main = getMain(); // Trigger boot  (!?!?)
            autostart.forEach(s -> {
                try {
                    main.addDependency(EvergreenService.locate(context, s), State.RUNNING);
                } catch (InputValidationException e) {
                    logger.atError().setCause(e).log("Unable to add auto-starting dependency {} to main", s);
                }
            });
        } catch (Throwable ex) {
            logger.atError().setEventType("system-boot-error").setCause(ex)
                    .log("***BOOT FAILED, SWITCHING TO FALLBACKMAIN*** ");
            mainServiceName = "fallbackMain";
            try {
                getMain(); // trigger fallback boot
            } catch (Throwable t) {
                logger.atError().setEventType("system-boot-error").setCause(t)
                        .log("***FALLBACK BOOT FAILED, ABANDON ALL HOPE*** ");
            }
        }
        writeEffectiveConfig();
        try {
            logger.atInfo().setEventType("system-start").addKeyValue("main", getMain()).log();
            startupAllServices();
        } catch (Throwable ex) {
            logger.atError().setEventType("service-start-error").setCause(ex).log();
        }
        return this;
    }

    private boolean ensureCreated(Path p) {
        try {
            Files.createDirectories(p,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return true;
        } catch (IOException ex) {
            logger.atError().setEventType("file-path-create-error").setCause(ex).addKeyValue("filePath", p).log();
            return false;
        }
    }

    public EvergreenService getMain() throws Throwable {
        EvergreenService m = mainService;
        if (m == null) {
            m = mainService = EvergreenService.locate(context, mainServiceName);
        }
        return m;
    }

    public void installCliTool(URL resource) {
        try {
            String dp = resource.getPath();
            int sl = dp.lastIndexOf('/');
            if (sl >= 0) {
                dp = dp.substring(sl + 1);
            }
            Path dest = clitoolPath.resolve(dp);
            context.get(EZTemplates.class).rewrite(resource, dest);
            Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("r-xr-x---"));
        } catch (Throwable t) {
            logger.atError().setEventType("cli-install-error").setCause(t).log();
        }
    }

    public synchronized void clearODcache() {
        cachedOD = null;
    }

    public synchronized Collection<EvergreenService> orderedDependencies() {
        if (cachedOD != null) {
            return cachedOD;
        }
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
                if (sz == pending.size()) {
                    // didn't find anything to remove, there must be a cycle
                    break;
                }
            }
            return cachedOD = ready;
        } catch (Throwable ex) {
            logger.atError().setEventType("resolve-service-dependency-error").setCause(ex).log();
            return Collections.EMPTY_LIST;
        }
    }

    public void writeEffectiveConfig() {
        // TODO: what file extension should we use?  The syntax is yaml, but the semantics are "evergreen"
        writeEffectiveConfig(configPath.resolve("effectiveConfig.evg"));
    }

    /*
     * When a config file gets read, it gets woven together from fragments from
     * multiple sources.  This writes a fresh copy of the config file, as it is,
     * after the weaving-together process.
     */
    public void writeEffectiveConfig(Path p) {
        try (CommitableWriter out = CommitableWriter.abandonOnClose(p)) {
            writeConfig(out);  // this is all made messy because writeConfig closes it's output stream
            out.commit();
            logger.atInfo().setEventType("effective-config-dump-complete").addKeyValue("file", p).log();
        } catch (Throwable t) {
            logger.atInfo().setEventType("effective-config-dump-error").setCause(t).addKeyValue("file", p).log();
        }
    }

    public void startupAllServices() {
        if (broken) {
            return;
        }
        orderedDependencies().forEach(l -> {
            logger.atInfo().setEventType("service-install").addKeyValue("serviceName", l.getName()).log();
            l.requestStart();
        });
    }

    public void dump() {
        orderedDependencies().forEach(l -> {
            System.out.println(l.getName() + ": " + l.getState());
            if (l.getState().preceeds(State.RUNNING)) {
                l.forAllDependencies(d -> System.out.println("    " + d.getName() + ": " + d.getState()));
            }
        });
    }

    public void writeConfig(Writer w) {
        Map<String, Object> h = new LinkedHashMap<>();
        orderedDependencies().forEach(l -> {
            if (l != null) {
                h.put(l.getName(), l.config.toPOJO());
            }
        });
        try {
            JSON.std.with(new YAMLFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)).write(h, w);
        } catch (IOException ex) {
            logger.atError().setEventType("write-config-error").setCause(ex).log();
        }
    }

    public void shutdown() {
        shutdown(30);
    }

    public void shutdown(int timeoutSeconds) {
        if (broken) {
            return;
        }
        close(tlog);
        try {
            logger.atInfo().setEventType("system-shutdown").addKeyValue("main", getMain()).log();
            EvergreenService[] d = orderedDependencies().toArray(new EvergreenService[0]);
            for (int i = d.length; --i >= 0; ) { // shutdown in reverse order
                try {
                    d[i].close();
                } catch (Throwable t) {
                    logger.atError().setEventType("service-shutdown-error").addKeyValue("serviceName", d[i].getName())
                            .setCause(t).log();
                }
            }

            // Wait for tasks in the executor to end.
            ExecutorService executorService = context.get(ExecutorService.class);
            this.context.runOnPublishQueueAndWait(() -> {
                executorService.shutdown();
                logger.atInfo().setEventType("executor-service-shutdown-initiated").log();
            });
            executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            logger.atInfo().setEventType("executor-service-shutdown-complete").log();
        } catch (Throwable ex) {
            logger.atError().setEventType("system-shutdown-error").setCause(ex).log();
        }
    }

    public void shutdownNow() {
        shutdown(0);
    }

    public String deTilde(String s) {
        if (s.startsWith("~/")) {
            s = homePath.resolve(s.substring(2)).toString();
        }
        if (rootPath != null && s.startsWith("~root/")) {
            s = rootPath.resolve(s.substring(6)).toString();
        }
        if (configPath != null && s.startsWith("~config/")) {
            s = configPath.resolve(s.substring(8)).toString();
        }
        if (clitoolPath != null && s.startsWith("~bin/")) {
            s = clitoolPath.resolve(s.substring(5)).toString();
        }
        return s;
    }

    private String getArg() {
        return arg = args == null || argpos >= args.length ? done : args[argpos++];
    }

    public Collection<String> getServiceServerURLlist() {
        if (!serviceServerURLlistIsPopulated) {
            serviceServerURLlistIsPopulated = true;
            addServiceSearchURLs(System.getProperty("ServiceSearchList"));
            addServiceSearchURLs(System.getenv("ServiceSearchList"));
            addServiceSearchURLs(find("system", "ServiceSearchList"));
            addServiceSearchURL(EvergreenService.class.getResource("/config"));
        }
        return serviceServerURLlist;
    }

    public void addServiceSearchURLs(Object urls) {
        for (String s : Coerce.toStringArray(urls)) {
            addServiceSearchURL(s);
        }
    }

    public void addServiceSearchURL(Object url) {
        if (url != null) {
            String u = url.toString();
            if (!u.endsWith("/")) {
                u += "/";
            }
            if (!serviceServerURLlist.contains(u)) {
                serviceServerURLlist.add(u);
            }
        }
    }

    /**
     * Merge in new configuration values and new services.
     *
     * @param deploymentId give an ID to the task to run
     * @param timestamp    timestamp for all configuration values to use when merging (newer timestamps win)
     * @param newConfig    the map of new configuration
     * @return future which completes only once the config is merged and all the services in the config are running
     */
    public Future<Void> mergeInNewConfig(String deploymentId, long timestamp, Map<Object, Object> newConfig) {
        CompletableFuture<Void> totallyCompleteFuture = new CompletableFuture<>();

        Map<String, CountDownLatch> latches = new HashMap<>();
        newConfig.forEach((key, v) -> latches.put((String) key, new CountDownLatch(1)));

        EvergreenService.GlobalStateChangeListener listener = (service, was) -> {
            if (newConfig.containsKey(service.getName()) && service.getState().equals(State.RUNNING)) {
                latches.get(service.getName()).countDown();
            }
            if (latches.values().stream().allMatch(c -> c.getCount() <= 0)) {
                totallyCompleteFuture.complete(null);
            }
        };

        totallyCompleteFuture.thenRun(() -> {
            context.removeGlobalStateChangeListener(listener);
        });

        context.get(UpdateSystemSafelyService.class).addUpdateAction(deploymentId, () -> {
            context.runOnPublishQueueAndWait(() -> {
                try {
                    mergeMap(timestamp, newConfig);
                    context.addGlobalStateChangeListener(listener);

                    newConfig.keySet().forEach(serviceName -> {
                        EvergreenService eg = EvergreenService.locate(context, (String) serviceName);
                        if (eg == null) {
                            logger.error("Could not locate EvergreenService for modified service {}", serviceName);
                        } else if (State.NEW.equals(eg.getState())) {
                            eg.requestStart();
                        }
                    });

                } catch (Throwable e) {
                    totallyCompleteFuture.completeExceptionally(e);
                }
            });
        });

        return totallyCompleteFuture;
    }
}
