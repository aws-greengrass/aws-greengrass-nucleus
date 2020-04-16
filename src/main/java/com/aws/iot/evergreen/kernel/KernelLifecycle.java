/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.kernel;

import com.aws.iot.evergreen.config.ConfigurationWriter;
import com.aws.iot.evergreen.dependency.EZPlugins;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.exceptions.InputValidationException;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Exec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.iot.evergreen.util.Utils.close;
import static com.aws.iot.evergreen.util.Utils.deepToString;

public class KernelLifecycle {
    private static final Logger logger = LogManager.getLogger(KernelLifecycle.class);

    private final Kernel kernel;
    private final KernelCommandLine kernelCommandLine;
    private final Map<String, Class<?>> serviceImplementors = new HashMap<>();
    private ConfigurationWriter tlog;
    private EvergreenService mainService;
    private final AtomicBoolean isShutdownInitiated = new AtomicBoolean(false);

    public KernelLifecycle(Kernel kernel, KernelCommandLine kernelCommandLine) {
        this.kernel = kernel;
        this.kernelCommandLine = kernelCommandLine;
    }

    /**
     * Startup the Kernel and all services.
     */
    public void launch() {
        logger.atInfo().log("root path = {}. config path = {}", kernel.rootPath,
                kernel.configPath);
        Exec.setDefaultEnv("EVERGREEN_HOME", kernel.rootPath.toString());

        // Must be called before everything else so that these are available to be
        // referenced by main/dependencies of main
        final Queue<String> autostart = findBuiltInServicesAndPlugins(); //NOPMD

        try {
            mainService = kernel.locate(kernelCommandLine.mainServiceName);
        } catch (ServiceLoadException sle) {
            RuntimeException rte =
                    new RuntimeException("Cannot load main service", sle);
            logger.atError("system-boot-error", rte).log();
            throw rte;
        }

        autostart.forEach(s -> {
            try {
                mainService.addOrUpdateDependency(kernel.locate(s), State.RUNNING, true);
            } catch (ServiceLoadException se) {
                logger.atError().setCause(se).log("Unable to load service {}", s);
            } catch (InputValidationException e) {
                logger.atError().setCause(e).log("Unable to add auto-starting dependency {} to main", s);
            }
        });

        Path transactionLogPath = kernel.configPath.resolve("config.tlog");
        Path configurationFile = kernel.configPath.resolve("config.yaml");
        try {
            if (kernelCommandLine.haveRead) {
                // new config file came in from the outside
                kernel.writeEffectiveConfig(configurationFile);
                Files.deleteIfExists(transactionLogPath);
            } else {
                if (Files.exists(configurationFile)) {
                    kernel.config.read(configurationFile);
                }
                if (Files.exists(transactionLogPath)) {
                    kernel.config.read(transactionLogPath);
                }
            }
            tlog = ConfigurationWriter.logTransactionsTo(kernel.config, transactionLogPath);
            tlog.flushImmediately(true);
        } catch (IOException ioe) {
            logger.atError().setEventType("system-config-error").setCause(ioe).log();
            throw new RuntimeException(ioe);
        }

        kernel.writeEffectiveConfig();
        logger.atInfo().setEventType("system-start").addKeyValue("main", kernel.getMain()).log();
        startupAllServices();
    }

    private Queue<String> findBuiltInServicesAndPlugins() {
        Queue<String> autostart = new LinkedList<>();
        try {
            EZPlugins pim = kernel.context.get(EZPlugins.class);
            pim.withCacheDirectory(kernel.rootPath.resolve("plugins"));
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
                kernel.context.put("service-implementors", serviceImplementors);
            }
            logger.atInfo().log("serviceImplementors: {}", deepToString(serviceImplementors));
        } catch (IOException t) {
            logger.atError().log("Error launching plugins", t);
        }
        return autostart;
    }

    /**
     * Make all services startup in order.
     */
    public void startupAllServices() {
        kernel.orderedDependencies().forEach(l -> {
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
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public void shutdown(int timeoutSeconds) {
        if (!isShutdownInitiated.compareAndSet(false, true)) {
            logger.info("Shutdown already initiated, returning...");
            return;
        }
        close(tlog);
        try {
            logger.atInfo().setEventType("system-shutdown").addKeyValue("main", getMain()).log();
            EvergreenService[] d = kernel.orderedDependencies().toArray(new EvergreenService[0]);

            CompletableFuture<?>[] arr = new CompletableFuture[d.length];
            for (int i = d.length - 1; i >= 0; --i) { // shutdown in reverse order
                String serviceName = d[i].getName();
                try {
                    arr[i] = (CompletableFuture<?>) d[i].close();
                    arr[i].whenComplete((v, t) -> {
                        if (t != null) {
                            logger.atError().setEventType("service-shutdown-error")
                                    .addKeyValue("serviceName", serviceName)
                                    .setCause(t).log();
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
            ScheduledExecutorService scheduledExecutorService = kernel.context.get(ScheduledExecutorService.class);
            ExecutorService executorService = kernel.context.get(ExecutorService.class);
            kernel.context.runOnPublishQueueAndWait(() -> {
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

    EvergreenService getMain() {
        return mainService;
    }
}
