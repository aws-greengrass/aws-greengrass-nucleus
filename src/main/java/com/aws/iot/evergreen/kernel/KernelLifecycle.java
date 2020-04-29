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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
        logger.atInfo().log("root path = {}. config path = {}", kernel.getRootPath(),
                kernel.getConfigPath());

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
                logger.atError().log("Unable to load service {}", s, se);
            } catch (InputValidationException e) {
                logger.atError().log("Unable to add auto-starting dependency {} to main", s, e);
            }
        });

        Path transactionLogPath = kernel.getConfigPath().resolve("config.tlog");
        Path configurationFile = kernel.getConfigPath().resolve("config.yaml");
        try {
            if (kernelCommandLine.haveRead) {
                // new config file came in from the outside
                kernel.writeEffectiveConfig(configurationFile);
                Files.deleteIfExists(transactionLogPath);
            } else {
                if (Files.exists(configurationFile)) {
                    kernel.getConfig().read(configurationFile);
                }
                if (Files.exists(transactionLogPath)) {
                    kernel.getConfig().read(transactionLogPath);
                }
            }
            tlog = ConfigurationWriter.logTransactionsTo(kernel.getConfig(), transactionLogPath);
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
            EZPlugins pim = kernel.getContext().get(EZPlugins.class);
            pim.withCacheDirectory(kernel.getRootPath().resolve("plugins"));
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
                kernel.getContext().put("service-implementors", serviceImplementors);
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
        kernel.orderedDependencies().forEach(EvergreenService::requestStart);
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
                    arr[i] = d[i].close();
                    arr[i].whenComplete((v, t) -> {
                        if (t != null) {
                            logger.atError("service-shutdown-error", t)
                                    .kv(EvergreenService.SERVICE_NAME_KEY, serviceName).log();
                        }
                    });
                } catch (Throwable t) {
                    logger.atError("service-shutdown-error", t)
                            .kv(EvergreenService.SERVICE_NAME_KEY, serviceName).log();
                    arr[i] = CompletableFuture.completedFuture(Optional.empty());
                }
            }

            try {
                CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(arr);
                logger.atInfo().log("Waiting for services to shutdown");
                combinedFuture.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                List<String> unclosedServices = IntStream.range(0, arr.length)
                        .filter((i) -> !arr[i].isDone() || arr[i].isCompletedExceptionally())
                        .mapToObj((i) -> d[i].getName()).collect(Collectors.toList());
                logger.atError("services-shutdown-errored", e)
                        .kv("unclosedServices", unclosedServices)
                        .log();
            }

            // Wait for tasks in the executor to end.
            ScheduledExecutorService scheduledExecutorService = kernel.getContext().get(ScheduledExecutorService.class);
            ExecutorService executorService = kernel.getContext().get(ExecutorService.class);
            kernel.getContext().runOnPublishQueueAndWait(() -> {
                executorService.shutdown();
                scheduledExecutorService.shutdown();
                logger.atInfo().setEventType("executor-service-shutdown-initiated").log();
            });
            // TODO: Timeouts should not be additive (ie. our timeout should be for this entire method, not
            //  each timeout-able part of the method.
            logger.atInfo().log("Waiting for executors to shutdown");
            executorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            scheduledExecutorService.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            // Shutdown executors by interrupting running threads
            executorService.shutdownNow();
            scheduledExecutorService.shutdownNow();
            //TODO: this needs to be changed once state machine thread is using the shared executor
            logger.atInfo("executor-service-shutdown-complete").log();
            logger.atInfo("context-shutdown-initiated").log();
            kernel.getContext().close();
            logger.atInfo("context-shutdown-complete").log();
        } catch (Throwable ex) {
            logger.atError("system-shutdown-error", ex).log();
        }
    }

    EvergreenService getMain() {
        return mainService;
    }
}
