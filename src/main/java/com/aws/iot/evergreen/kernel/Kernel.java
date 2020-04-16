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

import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import javax.inject.Singleton;

/**
 * Evergreen-kernel.
 */
public class Kernel {
    private static final Logger logger = LogManager.getLogger(Kernel.class);
    public final Context context;
    public final Configuration config;

    public Path rootPath;
    public Path configPath;
    public Path clitoolPath;
    public Path workPath;
    public Path packageStorePath;

    private final KernelCommandLine kernelCommandLine;
    private final KernelLifecycle kernelLifecycle;
    private final DeploymentConfigMerger deploymentConfigMerger;
    private Collection<EvergreenService> cachedOD = Collections.emptyList();

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
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
        kernelCommandLine = new KernelCommandLine(this);
        kernelLifecycle = new KernelLifecycle(this, kernelCommandLine);
        deploymentConfigMerger = new DeploymentConfigMerger(this);
        context.put(KernelCommandLine.class, kernelCommandLine);
        context.put(KernelLifecycle.class, kernelLifecycle);
        context.put(DeploymentConfigMerger.class, deploymentConfigMerger);
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

    public synchronized void clearODcache() {
        cachedOD.clear();
    }

    /**
     * Get a list of all dependencies in order (with the main service as the last).
     *
     * @return collection of services in dependency order
     */
    public synchronized Collection<EvergreenService> orderedDependencies() {
        if (!cachedOD.isEmpty()) {
            return cachedOD;
        }

        if (getMain() == null) {
            return Collections.emptyList();
        }

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
        ConfigurationWriter.logTransactionsTo(config, transactionLogPath).flushImmediately(true);
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
                h.put(l.getName(), l.getServiceConfig().toPOJO());
            }
        });
        try {
            JSON.std.with(new YAMLFactory().disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)).write(h, w);
        } catch (IOException ex) {
            logger.atError().setEventType("write-config-error").setCause(ex).log();
        }
    }

    public Topics findServiceTopic(String serviceName) {
        return config.findTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, serviceName);
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
        return deploymentConfigMerger.mergeInNewConfig(deploymentId, timestamp, newConfig);
    }

    /**
     * Locate an EvergreenService by name in the kernel context.
     *
     * @param name    name of the service to find
     * @return found service or null
     * @throws ServiceLoadException if service cannot load
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public EvergreenService locate(String name) throws ServiceLoadException {
        return context.getValue(EvergreenService.class, name).computeObjectIfEmpty(v -> {
            Configuration configuration = context.get(Configuration.class);
            Topics serviceRootTopics = configuration.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC, name);
            if (serviceRootTopics.isEmpty()) {
                logger.atWarn().setEventType("service-config-not-found").kv(EvergreenService.SERVICE_NAME_KEY, name)
                        .log("Could not find service definition in configuration file");
            } else {
                logger.atInfo().setEventType("service-config-found").kv(EvergreenService.SERVICE_NAME_KEY, name)
                        .log("Found service definition in configuration file");
            }

            // try to find service implementation class from plugins.
            Class<?> clazz = null;
            Node n = serviceRootTopics.findLeafChild("class");

            if (n != null) {
                String cn = Coerce.toString(n);
                try {
                    clazz = Class.forName(cn);
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't load service class from " + cn, ex);
                }
            }

            if (clazz == null) {
                Map<String, Class<?>> si = context.getIfExists(Map.class, "service-implementors");
                if (si != null) {
                    logger.atDebug().kv(EvergreenService.SERVICE_NAME_KEY, name).log("Attempt to load service from "
                            + "plugins");
                    clazz = si.get(name);
                }
            }
            EvergreenService ret;
            // If found class, try to load service class from plugins.
            if (clazz != null) {
                try {
                    Constructor<?> ctor = clazz.getConstructor(Topics.class);
                    ret = (EvergreenService) ctor.newInstance(serviceRootTopics);
                    if (clazz.getAnnotation(Singleton.class) != null) {
                        context.put(ret.getClass(), v);
                    }
                    logger.atInfo().setEventType("evergreen-service-loaded").kv(EvergreenService.SERVICE_NAME_KEY,
                            ret.getName())
                            .log();
                    return ret;
                } catch (Throwable ex) {
                    throw new ServiceLoadException("Can't create Evergreen Service instance " + clazz.getSimpleName(),
                            ex);
                }
            }

            if (serviceRootTopics.isEmpty()) {
                throw new ServiceLoadException("No matching definition in system model for: " + name);
            }

            // if not found, initialize GenericExternalService
            try {
                ret = new GenericExternalService(serviceRootTopics);
                logger.atInfo().setEventType("generic-service-loaded").kv(EvergreenService.SERVICE_NAME_KEY,
                        ret.getName())
                        .log();
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
