/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.ConfigurationWriter;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.deployment.DeploymentConfigMerger;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.CommitableWriter;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import javax.annotation.Nullable;
import javax.inject.Singleton;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

/**
 * Evergreen-kernel.
 */
public class Kernel {
    private static final Logger logger = LogManager.getLogger(Kernel.class);
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
    private Path packageStorePath;

    @Setter(AccessLevel.PACKAGE)
    private KernelCommandLine kernelCommandLine;
    @Setter(AccessLevel.PACKAGE)
    private KernelLifecycle kernelLifecycle;

    private Collection<EvergreenService> cachedOD = null;

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
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            shutdown();
            // Shutdown Log4j because we disabled it's shutdown hook
            org.apache.logging.log4j.LogManager.shutdown();
        }));
        kernelCommandLine = new KernelCommandLine(this);
        kernelLifecycle = new KernelLifecycle(this, kernelCommandLine);
        context.put(KernelCommandLine.class, kernelCommandLine);
        context.put(KernelLifecycle.class, kernelLifecycle);
        context.put(DeploymentConfigMerger.class, new DeploymentConfigMerger(this));
        context.put(Clock.class, Clock.systemUTC());
    }

    /**
     * Find the service that a Node belongs to (or null if it is not under a service).
     *
     * @param node node to identify the service it belongs to
     * @return service name or null
     */
    public static String findServiceForNode(Node node) {
        List<String> p = node.path();
        int idx = p.lastIndexOf(SERVICES_NAMESPACE_TOPIC);
        if (idx <= 0) {
            return null;
        }
        return p.get(idx - 1);
    }

    /**
     * Startup the Kernel and all services.
     */
    public Kernel launch() {
        kernelLifecycle.launch();
        return this;
    }

    public void shutdown() {
        kernelLifecycle.shutdown();
    }

    public void shutdown(int timeoutSeconds) {
        kernelLifecycle.shutdown(timeoutSeconds);
    }

    /**
     * Get a reference to the main service.
     */
    public EvergreenService getMain() {
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
    public synchronized Collection<EvergreenService> orderedDependencies() {
        if (cachedOD != null) {
            return cachedOD;
        }

        if (getMain() == null) {
            return Collections.emptyList();
        }

        final HashSet<EvergreenService> pendingDependencyServices = new LinkedHashSet<>();
        getMain().putDependenciesIntoSet(pendingDependencyServices);
        final HashSet<EvergreenService> dependencyFoundServices = new LinkedHashSet<>();
        while (!pendingDependencyServices.isEmpty()) {
            int sz = pendingDependencyServices.size();
            pendingDependencyServices.removeIf(pendingService -> {
                if (dependencyFoundServices.containsAll(pendingService.getDependencies().keySet())) {
                    dependencyFoundServices.add(pendingService);
                    return true;
                }
                return false;
            });
            if (sz == pendingDependencyServices.size()) {
                // didn't find anything to remove, there must be a cycle
                break;
            }
        }
        return cachedOD = dependencyFoundServices;
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
        Map<String, Object> serviceMap = new LinkedHashMap<>();
        orderedDependencies().forEach(l -> {
            if (l != null) {
                serviceMap.put(l.getName(), l.getServiceConfig().toPOJO());
            }
        });

        Map<String, Object> configMap = new HashMap<>();
        configMap.put(SERVICES_NAMESPACE_TOPIC, serviceMap);
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
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public EvergreenService locate(String name) throws ServiceLoadException {
        return context.getValue(EvergreenService.class, name).computeObjectIfEmpty(v -> {
            Topics serviceRootTopics = findServiceTopic(name);

            Class<?> clazz = null;
            if (serviceRootTopics != null) {
                Node n = serviceRootTopics.findLeafChild("class");

                if (n != null) {
                    String cn = Coerce.toString(n);
                    try {
                        clazz = Class.forName(cn);
                    } catch (Throwable ex) {
                        throw new ServiceLoadException("Can't load service class from " + cn, ex);
                    }
                }
            }

            // try to find service implementation class from plugins.
            if (clazz == null) {
                Map<String, Class<?>> si = context.getIfExists(Map.class, "service-implementors");
                if (si != null) {
                    logger.atInfo().kv(EvergreenService.SERVICE_NAME_KEY, name)
                            .log("Attempt to load service from plugins");
                    clazz = si.get(name);
                }
            }

            EvergreenService ret;
            // If found class, try to load service class from plugins.
            if (clazz != null) {
                try {
                    // Lookup the service topics here because the Topics passed into the EvergreenService
                    // constructor must not be null
                    Topics topics = config.lookupTopics(SERVICES_NAMESPACE_TOPIC, name);

                    try {
                        Constructor<?> ctor = clazz.getConstructor(Topics.class);
                        ret = (EvergreenService) ctor.newInstance(topics);
                    } catch (NoSuchMethodException e) {
                        // If the basic constructor doesn't exist, then try injecting from the context
                        ret = (EvergreenService) context.newInstance(clazz);
                    }

                    if (clazz.getAnnotation(Singleton.class) != null) {
                        context.put(ret.getClass(), v);
                    }

                    logger.atInfo("evergreen-service-loaded")
                            .kv(EvergreenService.SERVICE_NAME_KEY, ret.getName()).log();
                    return ret;
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't create Evergreen Service instance " + clazz.getSimpleName(),
                            ex);
                }
            }

            if (serviceRootTopics == null || serviceRootTopics.isEmpty()) {
                throw new ServiceLoadException("No matching definition in system model for: " + name);
            }

            // if not found, initialize GenericExternalService
            try {
                ret = new GenericExternalService(serviceRootTopics);
                logger.atInfo("generic-service-loaded")
                        .kv(EvergreenService.SERVICE_NAME_KEY, ret.getName()).log();
            } catch (Throwable ex) {
                throw new ServiceLoadException("Can't create generic service instance " + name, ex);
            }
            return ret;
        });
    }

    public Kernel parseArgs(String... args) {
        kernelCommandLine.parseArgs(args);
        return this;
    }
}
