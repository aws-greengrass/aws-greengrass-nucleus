/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.ConfigurationWriter;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeploymentConfigMerger;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.activator.DeploymentActivatorFactory;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.exceptions.ServiceUpdateException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.Deployment.DeploymentStage;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.CommitableWriter;
import com.aws.greengrass.util.DependencyOrder;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.jr.ob.JSON;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;
import javax.inject.Singleton;

import static com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory.CONTEXT_COMPONENT_SERVICE_ENDPOINT;
import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.dependency.EZPlugins.JAR_FILE_EXTENSION;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENTS_QUEUE;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_REBOOT;
import static com.aws.greengrass.deployment.bootstrap.BootstrapSuccessCode.REQUEST_RESTART;
import static com.aws.greengrass.easysetup.DeviceProvisioningHelper.STAGE_TO_ENDPOINT_FORMAT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.KernelCommandLine.MAIN_SERVICE_NAME;

/**
 * Greengrass-kernel.
 */
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class Kernel {
    private static final Logger logger = LogManager.getLogger(Kernel.class);
    protected static final String CONTEXT_SERVICE_IMPLEMENTERS = "service-implementers";
    public static final String SERVICE_CLASS_TOPIC_KEY = "class";
    public static final String SERVICE_TYPE_TOPIC_KEY = "componentType";
    public static final String SERVICE_TYPE_TO_CLASS_MAP_KEY = "componentTypeToClassMap";
    private static final String PLUGIN_SERVICE_TYPE_NAME = "plugin";
    static final String DEFAULT_CONFIG_YAML_FILE = "config.yaml";
    static final String DEFAULT_CONFIG_TLOG_FILE = "config.tlog";

    @Getter
    private final Context context;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Configuration config;

    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path rootPath;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path configPath;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path clitoolPath;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path workPath;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path componentStorePath;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path kernelAltsPath;
    @Getter
    @Setter(AccessLevel.PACKAGE)
    private Path deploymentsPath;

    @Setter(AccessLevel.PACKAGE)
    private KernelCommandLine kernelCommandLine;
    @Setter(AccessLevel.PACKAGE)
    private KernelLifecycle kernelLifecycle;

    private Collection<GreengrassService> cachedOD = null;

    /**
     * Construct the Kernel and global Context.
     */
    public Kernel() {
        context = new Context();
        config = new Configuration(context);
        context.put(Configuration.class, config);
        context.put(Kernel.class, this);
        ScheduledThreadPoolExecutor ses = new ScheduledThreadPoolExecutor(4);
        ExecutorService executorService = Executors.newCachedThreadPool();
        context.put(ScheduledThreadPoolExecutor.class, ses);
        context.put(ScheduledExecutorService.class, ses);
        context.put(Executor.class, executorService);
        context.put(ExecutorService.class, executorService);
        context.put(ThreadPoolExecutor.class, ses);

        Thread.setDefaultUncaughtExceptionHandler(new KernelExceptionHandler());
        Runtime.getRuntime().addShutdownHook(new Thread(() -> this.shutdown(-1)));

        kernelCommandLine = new KernelCommandLine(this);
        kernelLifecycle = new KernelLifecycle(this, kernelCommandLine);
        context.put(KernelCommandLine.class, kernelCommandLine);
        context.put(KernelLifecycle.class, kernelLifecycle);
        context.put(DeploymentConfigMerger.class, new DeploymentConfigMerger(this));
        context.put(DeploymentActivatorFactory.class, new DeploymentActivatorFactory(this));
        context.put(Clock.class, Clock.systemUTC());
        Map<String, String> typeToClassMap = new ConcurrentHashMap<>();
        typeToClassMap.put("lambda", "com.aws.greengrass.lambdamanager.UserLambdaService");
        context.put(SERVICE_TYPE_TO_CLASS_MAP_KEY, typeToClassMap);
    }

    /**
     * Find the service that a Node belongs to (or null if it is not under a service).
     *
     * @param node node to identify the service it belongs to
     * @return service name or null
     */
    public static String findServiceForNode(Node node) {
        String[] p = node.path();
        for (int i = 0; i < p.length - 1; i++) {
            if (SERVICES_NAMESPACE_TOPIC.equals(p[i])) {
                return p[i + 1];
            }
        }
        return null;
    }

    /**
     * Startup the Kernel and all services.
     */
    @SuppressWarnings("PMD.MissingBreakInSwitch")
    public Kernel launch() {
        BootstrapManager bootstrapManager = kernelCommandLine.getBootstrapManager();
        DeploymentDirectoryManager deploymentDirectoryManager = kernelCommandLine.getDeploymentDirectoryManager();
        KernelAlternatives kernelAlts = kernelCommandLine.getKernelAlternatives();
        DeploymentStage stage = kernelAlts.determineDeploymentStage(bootstrapManager, deploymentDirectoryManager);
        switch (stage) {
            case BOOTSTRAP:
                logger.atInfo().kv("deploymentStage", stage).log("Resume deployment");
                int exitCode;
                try {
                    exitCode = bootstrapManager.executeAllBootstrapTasksSequentially(
                            deploymentDirectoryManager.getBootstrapTaskFilePath());
                    if (!bootstrapManager.hasNext()) {
                        logger.atInfo().log("Completed all bootstrap tasks. Continue to activate deployment changes");
                    }
                    // If exitCode is 0, which happens when all bootstrap tasks are completed, restart in new launch
                    // directories and verify handover is complete. As a result, exit code 0 is treated as 100 here.
                    logger.atInfo().log((exitCode == REQUEST_REBOOT ? "device reboot" : "kernel restart")
                            + " requested to complete bootstrap task");

                    shutdown(30, exitCode == REQUEST_REBOOT ? REQUEST_REBOOT : REQUEST_RESTART);
                } catch (ServiceUpdateException | IOException e) {
                    logger.atInfo().log("Deployment bootstrap failed", e);
                    try {
                        kernelAlts.prepareRollback();
                        Deployment deployment = deploymentDirectoryManager.readDeploymentMetadata();
                        deployment.setStageDetails(e.getMessage());
                        deploymentDirectoryManager.writeDeploymentMetadata(deployment);
                    } catch (IOException ioException) {
                        logger.atError().setCause(ioException).log("Something went wrong while preparing for rollback");
                    }
                    shutdown(30, 2);
                }
                break;
            case KERNEL_ACTIVATION:
            case KERNEL_ROLLBACK:
                logger.atInfo().kv("deploymentStage", stage).log("Resume deployment");
                LinkedBlockingQueue<Deployment> deploymentsQueue = new LinkedBlockingQueue<>();
                context.put(DEPLOYMENTS_QUEUE, deploymentsQueue);
                try {
                    Deployment deployment = deploymentDirectoryManager.readDeploymentMetadata();
                    deployment.setDeploymentStage(stage);
                    deploymentsQueue.add(deployment);
                } catch (IOException e) {
                    logger.atError().setCause(e)
                            .log("Failed to load information for the ongoing deployment. Proceed as default");
                }
                // fall through to launch kernel
            default:
                kernelLifecycle.launch();
                break;
        }
        return this;
    }

    /**
     * Shutdown Kernel but not exit the process.
     */
    public void shutdown() {
        shutdown(30);
    }

    /**
     * Shutdown Kernel within the timeout but not exit the process.
     *
     * @param timeoutSeconds Timeout in seconds
     */
    public void shutdown(int timeoutSeconds) {
        kernelLifecycle.shutdown(timeoutSeconds);
    }

    /**
     * Shutdown Kernel within the timeout and exit the process with the given code.
     *
     * @param timeoutSeconds Timeout in seconds
     * @param exitCode       exit code
     */
    public void shutdown(int timeoutSeconds, int exitCode) {
        kernelLifecycle.shutdown(timeoutSeconds, exitCode);
    }

    /**
     * Get a reference to the main service.
     */
    public GreengrassService getMain() {
        return kernelLifecycle.getMain();
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
    public synchronized Collection<GreengrassService> orderedDependencies() {
        if (cachedOD != null) {
            return cachedOD;
        }

        if (getMain() == null) {
            return Collections.emptyList();
        }

        final HashSet<GreengrassService> pendingDependencyServices = new LinkedHashSet<>();
        getMain().putDependenciesIntoSet(pendingDependencyServices);
        final LinkedHashSet<GreengrassService> dependencyFoundServices = new DependencyOrder<GreengrassService>()
                .computeOrderedDependencies(pendingDependencyServices, s -> s.getDependencies().keySet());

        return cachedOD = dependencyFoundServices;
    }

    public void writeEffectiveConfig() {
        // TODO: what file extension should we use?  The syntax is yaml, but the semantics are "evergreen"
        writeEffectiveConfig(configPath.resolve(DEFAULT_CONFIG_YAML_FILE));
    }

    /**
     * When a config file gets read, it gets woven together from fragments from multiple sources.  This writes a fresh
     * copy of the config file, as it is, after the weaving-together process.
     *
     * @param p Path to write the effective config into
     */
    public void writeEffectiveConfig(Path p) {
        try (CommitableWriter out = CommitableWriter.abandonOnClose(p)) {
            writeConfig(out);
            out.commit();
            logger.atInfo().setEventType("effective-config-dump-complete").addKeyValue("file", p).log();
        } catch (IOException t) {
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
        ConfigurationWriter.dump(config, transactionLogPath);
    }

    /**
     * Write the effective config into a {@link Writer}.
     *
     * @param w Writer to write config into
     */
    public void writeConfig(Writer w) {
        Map<String, Object> configMap = new HashMap<>();
        configMap.put(SERVICES_NAMESPACE_TOPIC, config.findTopics(SERVICES_NAMESPACE_TOPIC).toPOJO());
        configMap.put(DeviceConfiguration.SYSTEM_NAMESPACE_KEY,
                config.findTopics(DeviceConfiguration.SYSTEM_NAMESPACE_KEY).toPOJO());
        try {
            JSON.std.with(new YAMLFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)).write(configMap, w);
        } catch (IOException ex) {
            logger.atError().setEventType("write-config-error").setCause(ex).log();
        }
    }

    @Nullable
    public Topics findServiceTopic(String serviceName) {
        return config.findTopics(SERVICES_NAMESPACE_TOPIC, serviceName);
    }

    /**
     * Locate an EvergreenService by name in the kernel context.
     *
     * @param name name of the service to find
     * @return found service or null
     * @throws ServiceLoadException if service cannot load
     */
    @SuppressWarnings(
            {"UseSpecificCatch", "PMD.AvoidCatchingThrowable", "PMD.AvoidDeeplyNestedIfStmts", "PMD.ConfusingTernary"})
    public GreengrassService locate(String name) throws ServiceLoadException {
        return context.getValue(GreengrassService.class, name).computeObjectIfEmpty(v -> {
            Topics serviceRootTopics = findServiceTopic(name);

            Class<?> clazz = null;
            if (serviceRootTopics != null) {

                // Try locating all the dependencies first so that they'll all exist prior to their dependant.
                // This is to fix an ordering problem with plugins such as lambda manager. The plugin needs to be
                // located *before* the dependant is located so that the plugin has its jar loaded into the classloader.
                Topic dependenciesTopic = serviceRootTopics.findLeafChild(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
                if (dependenciesTopic != null && dependenciesTopic.getOnce() instanceof Collection) {
                    try {
                        for (Pair<String, DependencyType> p : GreengrassService
                                .parseDependencies((Collection<String>) dependenciesTopic.getOnce())) {
                            locate(p.getLeft());
                        }
                    } catch (ServiceLoadException | InputValidationException e) {
                        throw new ServiceLoadException("Unable to load service " + name, e);
                    }
                }

                Topic classTopic = serviceRootTopics.findLeafChild(SERVICE_CLASS_TOPIC_KEY);
                String className = null;

                // If a "class" is specified in the recipe, then use that
                if (classTopic != null) {
                    className = Coerce.toString(classTopic);
                } else {
                    Topic componentTypeTopic = serviceRootTopics.findLeafChild(SERVICE_TYPE_TOPIC_KEY);
                    // If a "componentType" is specified, then map that to a class
                    if (componentTypeTopic != null) {
                        className = ((Map<String, String>) context.getvIfExists(SERVICE_TYPE_TO_CLASS_MAP_KEY).get())
                                .get(Coerce.toString(componentTypeTopic).toLowerCase());
                        // If the mapping didn't exist and the component type is "plugin", then load the service from a
                        // plugin
                        if (className == null && Coerce.toString(componentTypeTopic).toLowerCase()
                                .equals(PLUGIN_SERVICE_TYPE_NAME)) {
                            clazz = locateExternalPlugin(name, serviceRootTopics);
                        }
                    }
                }

                if (className != null) {
                    try {
                        clazz = context.get(EZPlugins.class).forName(className);
                    } catch (Throwable ex) {
                        throw new ServiceLoadException("Can't load service class from " + className, ex);
                    }
                }
            }

            // try to find service implementation class from plugins.
            if (clazz == null) {
                Map<String, Class<?>> si = context.getIfExists(Map.class, CONTEXT_SERVICE_IMPLEMENTERS);
                if (si != null) {
                    logger.atInfo().kv(GreengrassService.SERVICE_NAME_KEY, name)
                            .log("Attempt to load service from plugins");
                    clazz = si.get(name);
                }
            }

            GreengrassService ret;
            // If found class, try to load service class from plugins.
            if (clazz != null) {
                try {
                    // Lookup the service topics here because the Topics passed into the EvergreenService
                    // constructor must not be null
                    Topics topics = config.lookupTopics(SERVICES_NAMESPACE_TOPIC, name);

                    try {
                        Constructor<?> ctor = clazz.getConstructor(Topics.class);
                        ret = (GreengrassService) ctor.newInstance(topics);
                    } catch (NoSuchMethodException e) {
                        // If the basic constructor doesn't exist, then try injecting from the context
                        ret = (GreengrassService) context.newInstance(clazz);
                    }

                    if (clazz.getAnnotation(Singleton.class) != null) {
                        context.put(ret.getClass(), v);
                    }
                    if (clazz.getAnnotation(ImplementsService.class) != null) {
                        topics.createLeafChild(VERSION_CONFIG_KEY)
                                .withNewerValue(0L, clazz.getAnnotation(ImplementsService.class).version());
                    }

                    logger.atInfo("evergreen-service-loaded").kv(GreengrassService.SERVICE_NAME_KEY, ret.getName())
                            .log();
                    return ret;
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't create Greengrass Service instance " + clazz.getSimpleName(),
                            ex);
                }
            }

            if (serviceRootTopics == null || serviceRootTopics.isEmpty()) {
                throw new ServiceLoadException("No matching definition in system model for: " + name);
            }

            // if not found, initialize GenericExternalService
            try {
                ret = new GenericExternalService(serviceRootTopics);
                logger.atInfo("generic-service-loaded").kv(GreengrassService.SERVICE_NAME_KEY, ret.getName()).log();
            } catch (Throwable ex) {
                throw new ServiceLoadException("Can't create generic service instance " + name, ex);
            }
            return ret;
        });
    }

    @SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CloseResource"})
    private Class<?> locateExternalPlugin(String name, Topics serviceRootTopics) throws ServiceLoadException {
        ComponentIdentifier componentId = ComponentIdentifier.fromServiceTopics(serviceRootTopics);
        Path pluginJar = context.get(ComponentStore.class).resolveArtifactDirectoryPath(componentId)
                .resolve(componentId.getName() + JAR_FILE_EXTENSION);
        if (!pluginJar.toFile().exists() || !pluginJar.toFile().isFile()) {
            throw new ServiceLoadException(
                    String.format("Unable to find %s because %s does not exist", name, pluginJar));
        }
        Class<?> clazz;
        try {
            AtomicReference<Class<?>> classReference = new AtomicReference<>();
            EZPlugins ezPlugins = context.get(EZPlugins.class);
            ezPlugins.loadPlugin(pluginJar, (sc) -> sc.matchClassesWithAnnotation(ImplementsService.class, (c) -> {
                // Only use the class whose name matches what we want
                ImplementsService serviceImplementation = c.getAnnotation(ImplementsService.class);
                if (serviceImplementation.name().equals(name)) {
                    if (classReference.get() != null) {
                        logger.atWarn().log("Multiple classes implementing service found in {} "
                                        + "for component {}. Using the first one found: {}", pluginJar, name,
                                classReference.get());
                        return;
                    }
                    classReference.set(c);
                }
            }));
            clazz = classReference.get();
        } catch (Throwable e) {
            throw new ServiceLoadException(String.format("Unable to load %s as a plugin", name), e);
        }
        if (clazz == null) {
            throw new ServiceLoadException(String.format(
                    "Unable to find %s. Could not find any ImplementsService annotation with the same name.", name));
        }
        return clazz;
    }


    /**
     * Get running custom root components, excluding the kernel's built-in services.
     *
     * @return returns name and version as a map
     */
    public Map<String, String> getRunningCustomRootComponents() {

        Map<String, String> rootPackageNameAndVersionMap = new HashMap<>();

        for (GreengrassService service : getMain().getDependencies().keySet()) {
            Topic version = service.getConfig().find(VERSION_CONFIG_KEY);
            // If the service is an autostart service then ignore it.
            if (service.isBuiltin()) {
                continue;
            }
            rootPackageNameAndVersionMap.put(service.getName(), Coerce.toString(version));
        }
        return rootPackageNameAndVersionMap;
    }

    /**
     * Parse kernel arguments and initialized configuration.
     *
     * @param args CLI args
     * @return Kernel instance
     */
    public Kernel parseArgs(String... args) {
        kernelCommandLine.parseArgs(args);
        config.lookupTopics(SERVICES_NAMESPACE_TOPIC, MAIN_SERVICE_NAME, SERVICE_LIFECYCLE_NAMESPACE_TOPIC);
        kernelLifecycle.initConfigAndTlog();
        setupCloudEndpoint();
        return this;
    }

    private void setupCloudEndpoint() {
        DeviceConfiguration deviceConfiguration = context.get(DeviceConfiguration.class);
        IotSdkClientFactory.EnvironmentStage stage;
        try {
            stage = IotSdkClientFactory.EnvironmentStage
                    .fromString(Coerce.toString(deviceConfiguration.getEnvironmentStage()));
        } catch (InvalidEnvironmentStageException e) {
            logger.atError().setCause(e).log("Caught exception while parsing kernel args");
            throw new RuntimeException(e);
        }

        String region = Coerce.toString(deviceConfiguration.getAWSRegion());
        String endpoint = String.format(STAGE_TO_ENDPOINT_FORMAT.get(stage), region);
        logger.atInfo().log("Configured to use Greengrass endpoint: {}", endpoint);
        context.put(CONTEXT_COMPONENT_SERVICE_ENDPOINT, endpoint);
    }

    /*
     * I added this method because it's really handy for any external service that's
     * accessing files.  But it's just a trampoline method, which is like a chalkboard
     * squeak.  They really bug me.  But then I noticed that Kernel.java is
     * filled with trampolines.  And two objects that are the target of the trampolines,
     * and which are otherwise unused.  It all gets much cleaner if kernelCommandline
     * and kernelLifecycle are just accessed through dependency injection where they're
     * needed.  I did this edit, and it got rid of a pile of code.  But it blew the
     * the unit tests out of the water.  mock(x) is actively injection-hostile.  I
     * started down the road of fixing the tests, but it got *way* out of hand.
     * So I went back to the trampoline form.  Someday this should be cleaned up.
     *                                                              - jag
     */
    public String deTilde(String filename) {
        return kernelCommandLine.deTilde(filename);
    }

}
