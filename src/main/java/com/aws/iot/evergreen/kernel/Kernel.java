/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.ConfigurationWriter;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.EZPlugins;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
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
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.util.Utils.HOME_PATH;
import static com.aws.iot.evergreen.util.Utils.close;
import static com.aws.iot.evergreen.util.Utils.deepToString;

/**
 * Evergreen-kernel.
 */
@SuppressFBWarnings(value = "EQ_DOESNT_OVERRIDE_EQUALS", justification = "We don't need equality")
@SuppressWarnings({"PMD.AvoidCatchingThrowable"})
public class Kernel extends Configuration /*implements Runnable*/ {
    private static final Logger logger = LogManager.getLogger(Kernel.class);
    private static final String done = " missing "; // unique marker
    public static final String MERGE_CONFIG_EVENT_KEY = "merge-config";
    private final Map<String, Class<?>> serviceImplementors = new HashMap<>();
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
    private final AtomicBoolean isShutdownInitiated = new AtomicBoolean(false);

    /**
     * Construct the Kernel and global Context.
     */
    public Kernel() {
        super(new Context());
        context.put(Configuration.class, this);
        context.put(Kernel.class, this);
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(4);
        ExecutorService executorService = Executors.newCachedThreadPool();
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, executorService);
        context.put(ExecutorService.class, executorService);
        context.put(ThreadPoolExecutor.class, ses);
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    public static void main(String[] args) {
        new Kernel().parseArgs(args).launch();
    }

    /**
     * Parse command line arguments before starting.
     *
     * @param args user-provided arguments
     */
    @SuppressWarnings("PMD.AssignmentInOperand")
    public Kernel parseArgs(String... args) {
        this.args = args;

        // Get root path from System Property/JVM argument. Default to home path
        String rootAbsolutePath = deTilde(System.getProperty("root", System.getProperty("user.home")));
        if (Utils.isEmpty(rootAbsolutePath) || !ensureCreated(Paths.get(rootAbsolutePath))) {
            logger.atError().log("{}: not a valid root directory", rootAbsolutePath);
            broken = true;
        }

        lookup("system", "rootpath").dflt(rootAbsolutePath)
                .subscribe((whatHappened, topic) -> initPaths((String) topic.getOnce()));

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
                        logger.atError().log("Can't read config file {}", arg, ex.getLocalizedMessage());
                    }
                    break;
                case "-log":
                case "-l":
                    lookup("log", "file").setValue(getArg());
                    break;
                case "-main":
                    mainServiceName = getArg();
                    break;
                case "-print":
                    writeConfig(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
                    break;
                default:
                    logger.atError().log("Undefined command line argument: {}", arg);
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
                    break;
            }
            return value;
        });
        return this;
    }

    private void initPaths(String rootAbsolutePath) {
        // init all paths
        rootPath = Paths.get(rootAbsolutePath);
        configPath = Paths.get(deTilde(configPathName));
        Exec.removePath(clitoolPath);
        clitoolPath = Paths.get(deTilde(clitoolPathName));
        Exec.addFirstPath(clitoolPath);
        workPath = Paths.get(deTilde(workPathName));
        Exec.setDefaultEnv("HOME", workPath.toString());
        ensureCreated(configPath);
        ensureCreated(clitoolPath);
        ensureCreated(rootPath);
        ensureCreated(workPath);
    }

    /**
     * Startup the Kernel and all services.
     */
    public Kernel launch() {
        logger.atInfo().log("root path = {}. config path = {}", rootPath, configPath);
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
                    logger.atError()
                            .log("{} needs to be a subclass of EvergreenService in order to use ImplementsService", cl);
                    return;
                }
                ImplementsService is = cl.getAnnotation(ImplementsService.class);
                if (is.autostart()) {
                    autostart.add(is.name());
                }
                serviceImplementors.put(is.name(), cl);
                logger.atInfo().log("Found Plugin: {}", cl.getSimpleName());
            });

            pim.loadCache();
            if (!serviceImplementors.isEmpty()) {
                context.put("service-implementors", serviceImplementors);
            }
            logger.atInfo().log("serviceImplementors: {}", deepToString(serviceImplementors));
        } catch (Throwable t) {
            logger.atError().log("Error launching plugins", t);
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
                tlog = ConfigurationWriter.logTransactionsTo(this, transactionLogPath);
                tlog.flushImmediately(true);
            } catch (Throwable ioe) {
                // Too early in the boot to log a message
                logger.atError().setEventType("system-config-error").setCause(ioe).log();
                broken = true;
                return this;
            }
        }
        if (broken) {
            throw new RuntimeException("Kernel is broken and cannot start. View logs for the reason why it is broken.");
        }
        if (!forReal) {
            context.put(ShellRunner.class, context.get(ShellRunner.Dryrun.class));
        }
        try {
            mainService = getMain();
            autostart.forEach(s -> {
                try {
                    mainService.addOrUpdateDependency(EvergreenService.locate(context, s), State.RUNNING, true);
                } catch (ServiceLoadException se) {
                    logger.atError().setCause(se).log("Unable to load service {}", s);
                } catch (InputValidationException e) {
                    logger.atError().setCause(e).log("Unable to add auto-starting dependency {} to main", s);
                }
            });
        } catch (Throwable ex) {
            logger.atError().setEventType("system-boot-error").setCause(ex)
                    .log("***BOOT FAILED, SWITCHING TO FALLBACKMAIN*** ");
            mainServiceName = "fallbackMain";
            try {
                mainService = getMain();
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

    /**
     * Get a reference to the main service.
     */
    public EvergreenService getMain() {
        if (mainService == null) {
            // TODO: move loading mainService into kernel launch
            try {
                mainService = EvergreenService.locate(context, mainServiceName);
            } catch (ServiceLoadException e) {
                logger.atError().setCause(e).log();
            }
        }
        return mainService;
    }

    /**
     * Install the CLI tool from the URL into the home directory.
     *
     * @param resource URL of the file to install
     */
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

    @SuppressWarnings("PMD.NullAssignment")
    public synchronized void clearODcache() {
        cachedOD = null;
    }

    /**
     * Get a list of all dependencies in order (with the main service as the last).
     *
     * @return collection of services in dependency order
     */
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

    /**
     * When a config file gets read, it gets woven together from fragments from
     * multiple sources.  This writes a fresh copy of the config file, as it is,
     * after the weaving-together process.
     *
     * @param p Path to write the effective config into
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

    /**
     * Write the effective config in the transaction log format.
     *
     * @param transactionLogPath path to write the file into
     * @throws IOException if writing fails
     */
    public void writeEffectiveConfigAsTransactionLog(Path transactionLogPath) throws IOException {
        ConfigurationWriter.logTransactionsTo(this, transactionLogPath).flushImmediately(true);
    }

    /**
     * Write the effective config into a {@link Writer}.
     *
     * @param w Writer to write config into
     */
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

    /**
     * Make all services startup in order.
     */
    public void startupAllServices() {
        if (broken) {
            return;
        }
        orderedDependencies().forEach(l -> {
            logger.atInfo().setEventType("service-install").addKeyValue(EvergreenService.SERVICE_NAME_KEY, l.getName())
                    .log();
            l.requestStart();
        });
    }

    public void shutdown() {
        shutdown(30);
    }

    /**
     * Shutdown all services and the kernel with given timeout.
     *
     * @param timeoutSeconds Timeout in seconds
     */
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED")
    @SuppressWarnings("PMD.AssignmentInOperand")
    public void shutdown(int timeoutSeconds) {
        if (broken) {
            return;
        }
        if (!isShutdownInitiated.compareAndSet(false, true)) {
            logger.info("Shutdown already initiated, returning...");
            return;
        }
        close(tlog);
        try {
            logger.atInfo().setEventType("system-shutdown").addKeyValue("main", getMain()).log();
            EvergreenService[] d = orderedDependencies().toArray(new EvergreenService[0]);

            CompletableFuture[] arr = new CompletableFuture[d.length];
            for (int i = d.length; --i >= 0; ) { // shutdown in reverse order
                String serviceName = d[i].getName();
                try {
                    arr[i] = (CompletableFuture) d[i].close();
                    arr[i].whenComplete((v, t) -> {
                        if (t != null) {
                            logger.atError().setEventType("service-shutdown-error")
                                    .addKeyValue("serviceName", serviceName)
                                    .setCause((Throwable) t).log();
                        }

                    });
                } catch (Throwable t) {
                    logger.atError().setEventType("service-shutdown-error")
                            .addKeyValue(EvergreenService.SERVICE_NAME_KEY, serviceName)
                            .setCause(t).log();
                    arr[i] = CompletableFuture.completedFuture(Optional.empty());
                }
            }

            try {
                CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(arr);
                combinedFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                logger.atError().setEventType("services-shutdown-errored").setCause(e).log();
            }

            // Wait for tasks in the executor to end.
            ScheduledExecutorService scheduledExecutorService = context.get(ScheduledExecutorService.class);
            ExecutorService executorService = context.get(ExecutorService.class);
            this.context.runOnPublishQueueAndWait(() -> {
                executorService.shutdown();
                scheduledExecutorService.shutdown();
                logger.atInfo().setEventType("executor-service-shutdown-initiated").log();
            });
            // TODO: Timeouts should not be additive (ie. our timeout should be for this entire method, not
            //  each timeout-able part of the method.
            executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            scheduledExecutorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            //TODO: this needs to be changed once state machine thread is using the shared executor
            logger.atInfo().setEventType("executor-service-shutdown-complete").log();
        } catch (Throwable ex) {
            logger.atError().setEventType("system-shutdown-error").setCause(ex).log();
        }
    }

    public void shutdownNow() {
        shutdown(0);
    }

    /**
     * Take a user-provided string which represents a path and resolve it to an absolute path.
     *
     * @param s String to resolve
     * @return resolved path
     */
    public String deTilde(String s) {
        if (s.startsWith("~/")) {
            s = HOME_PATH.resolve(s.substring(2)).toString();
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

    public Topics findServiceTopic(String name) {
        return this.findTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, name);
    }

    /**
     * Merge in new configuration values and new services.
     *
     * @param deploymentId give an ID to the task to run
     * @param timestamp    timestamp for all configuration values to use when merging (newer timestamps win)
     * @param newConfig    the map of new configuration
     * @return future which completes only once the config is merged and all the services in the config are running
     */
    @SuppressFBWarnings("NP_NONNULL_PARAM_VIOLATION") // https://github.com/findbugsproject/findbugs/issues/79
    public Future<Void> mergeInNewConfig(String deploymentId, long timestamp, Map<Object, Object> newConfig) {
        CompletableFuture<Void> totallyCompleteFuture = new CompletableFuture<>();

        if (newConfig.get("services") == null) {
            mergeMap(timestamp, newConfig);
            totallyCompleteFuture.complete(null);
            return totallyCompleteFuture;
        }

        Map<String, Object> serviceConfig = (Map<String, Object>) newConfig.get("services");
        List<String> removedServices = getRemovedServicesNames(serviceConfig);
        logger.atDebug(MERGE_CONFIG_EVENT_KEY).kv("removedServices", removedServices).log();

        Set<EvergreenService> servicesToTrack = new HashSet<>();
        context.get(UpdateSystemSafelyService.class).addUpdateAction(deploymentId, () -> {
            try {
                mergeMap(timestamp, newConfig);
                for (String serviceName : serviceConfig.keySet()) {
                    EvergreenService eg = EvergreenService.locate(context, serviceName);
                    if (State.NEW.equals(eg.getState())) {
                        eg.requestStart();
                    }
                    servicesToTrack.add(eg);
                }

                // wait until topic listeners finished processing mergeMap changes.
                context.runOnPublishQueueAndWait(() -> {
                    logger.atInfo(MERGE_CONFIG_EVENT_KEY)
                            .kv("serviceToTrack", servicesToTrack)
                            .log("applied new service config. Waiting for services to complete update");

                    // polling to wait for all services started.
                    context.get(ExecutorService.class).execute(() -> {
                        //TODO: Add timeout
                        try {
                            while (!totallyCompleteFuture.isCancelled()) {
                                if (servicesToTrack.stream().allMatch(service -> {
                                    if (!service.reachedDesiredState()) {
                                        return false;
                                    }
                                    State state = service.getState();
                                    if (State.RUNNING.equals(state) || State.FINISHED.equals(state)) {
                                        return true;
                                    }
                                    return false;
                                })) {
                                    break;
                                }
                                Thread.sleep(1000); // hardcoded
                            }
                            if (totallyCompleteFuture.isCancelled()) {
                                logger.atWarn(MERGE_CONFIG_EVENT_KEY).kv("deploymentId", deploymentId)
                                    .log("merge-config-cancelled");
                                return;
                            }

                            removeServices(removedServices);
                            logger.atInfo(MERGE_CONFIG_EVENT_KEY).kv("deploymentId", deploymentId)
                                .log("All services updated");

                            totallyCompleteFuture.complete(null);
                        } catch (Throwable t) {
                            //TODO: handle different throwables. Revert changes if applicable.
                            totallyCompleteFuture.completeExceptionally(t);
                        }
                    });
                });
            } catch (Throwable e) {
                totallyCompleteFuture.completeExceptionally(e);
            }
        });

        return totallyCompleteFuture;
    }

    private void removeServices(List<String> serviceToRemove) throws InterruptedException, ExecutionException {
        List<Future<Void>> serviceClosedFutures = new ArrayList<>();
        serviceToRemove.forEach(serviceName -> {
            try {
                EvergreenService eg = EvergreenService.locate(context, serviceName);
                serviceClosedFutures.add(eg.close());
            } catch (ServiceLoadException e) {
                logger.atError().setCause(e).addKeyValue("serviceName", serviceName)
                        .log("Could not locate EvergreenService to close service");
                // No need to handle the error when trying to stop a non-existing service.
            }
        });
        // waiting for removed service to close before removing reference and config entry
        for (Future<?> serviceClosedFuture : serviceClosedFutures) {
            serviceClosedFuture.get();
        }
        serviceToRemove.forEach(serviceName -> {
            context.remove(serviceName);
            findTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, serviceName).remove();
        });
    }

    //TODO: handle removing services that are running within in the JVM but defined via config
    private List<String> getRemovedServicesNames(Map<String, Object> serviceConfig) {
        return orderedDependencies().stream()
                .filter(evergreenService -> evergreenService instanceof GenericExternalService)
                .map(EvergreenService::getName).filter(serviceName -> !serviceConfig.containsKey(serviceName))
                .collect(Collectors.toList());

    }
}
