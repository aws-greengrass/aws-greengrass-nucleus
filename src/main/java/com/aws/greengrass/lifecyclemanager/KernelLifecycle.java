/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.ConfigurationWriter;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.ipc.IPCEventStreamService;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.ipc.modules.AuthorizationService;
import com.aws.greengrass.ipc.modules.ConfigStoreIPCService;
import com.aws.greengrass.ipc.modules.LifecycleIPCService;
import com.aws.greengrass.ipc.modules.MqttProxyIPCService;
import com.aws.greengrass.ipc.modules.PubSubIPCService;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.util.CommitableFile;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

import static com.aws.greengrass.util.Utils.close;
import static com.aws.greengrass.util.Utils.deepToString;

public class KernelLifecycle {
    private static final Logger logger = LogManager.getLogger(KernelLifecycle.class);
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final Kernel kernel;
    private final KernelCommandLine kernelCommandLine;
    private final Map<String, Class<?>> serviceImplementors = new HashMap<>();
    private final NucleusPaths nucleusPaths;
    // setter for unit testing
    @Setter(AccessLevel.PACKAGE)
    private List<Class<? extends Startable>> startables = Arrays.asList(IPCEventStreamService.class,
            AuthorizationService.class, ConfigStoreIPCService.class, LifecycleIPCService.class,
            PubSubIPCService.class, MqttProxyIPCService.class);
    @Getter
    private ConfigurationWriter tlog;
    private GreengrassService mainService;
    private final AtomicBoolean isShutdownInitiated = new AtomicBoolean(false);

    /**
     * Constructor.
     *
     * @param kernel kernel
     * @param kernelCommandLine command line
     * @param nucleusPaths paths
     */
    public KernelLifecycle(Kernel kernel, KernelCommandLine kernelCommandLine, NucleusPaths nucleusPaths) {
        this.kernel = kernel;
        this.kernelCommandLine = kernelCommandLine;
        this.nucleusPaths = nucleusPaths;
    }

    /**
     * Startup the Kernel and all services.
     */
    public void launch() {
        logger.atInfo("system-start").kv("version",
                kernel.getContext().get(DeviceConfiguration.class).getNucleusVersion())
                .kv("rootPath", nucleusPaths.rootPath())
                .kv("configPath", nucleusPaths.configPath()).log("Launch Nucleus");

        // Startup builtin non-services. This is blocking, so it will wait for them to be running.
        // This guarantees that IPC, for example, is running before any user code
        for (Class<? extends Startable> c : startables) {
            kernel.getContext().get(c).startup();
        }

        // Must be called before everything else so that these are available to be
        // referenced by main/dependencies of main
        final Queue<String> autostart = findBuiltInServicesAndPlugins(); //NOPMD

        mainService = kernel.locateIgnoreError(KernelCommandLine.MAIN_SERVICE_NAME);

        autostart.forEach(s -> {
            try {
                mainService.addOrUpdateDependency(kernel.locate(s), DependencyType.HARD, true);
            } catch (ServiceLoadException se) {
                logger.atError().log("Unable to load service {}", s, se);
            } catch (InputValidationException e) {
                logger.atError().log("Unable to add auto-starting dependency {} to main", s, e);
            }
        });

        kernel.writeEffectiveConfig();

        logger.atInfo().setEventType("system-start").addKeyValue("main", kernel.getMain()).log();
        startupAllServices();
    }

    void initConfigAndTlog(String configFilePath) {
        String configFileInput = kernelCommandLine.getProvidedConfigPathName();
        if (!Utils.isEmpty(configFileInput)) {
            logger.atWarn().kv("configFileInput", configFileInput).kv("configOverride", configFilePath)
                    .log("Detected ongoing deployment. Ignore the config file from input and use "
                    + "config file override");
        }
        kernelCommandLine.setProvidedConfigPathName(configFilePath);
        initConfigAndTlog();
    }

    void initConfigAndTlog() {
        try {
            Path transactionLogPath = nucleusPaths.configPath().resolve(Kernel.DEFAULT_CONFIG_TLOG_FILE);
            boolean readFromNonTlog = false;

            if (Objects.nonNull(kernelCommandLine.getProvidedConfigPathName())) {
                // If a config file is provided, kernel will use the provided file as a new base
                // and ignore existing config and tlog files.
                // This is used by the nucleus bootstrap workflow
                kernel.getConfig().read(kernelCommandLine.getProvidedConfigPathName());
                readFromNonTlog = true;
            } else {
                Path externalConfig = nucleusPaths.configPath().resolve(Kernel.DEFAULT_CONFIG_YAML_FILE_READ);
                boolean externalConfigFromCmd = Utils.isNotEmpty(kernelCommandLine.getProvidedInitialConfigPath());
                if (externalConfigFromCmd) {
                    externalConfig = Paths.get(kernelCommandLine.getProvidedInitialConfigPath());
                }

                Path bootstrapTlogPath = nucleusPaths.configPath().resolve(Kernel.DEFAULT_BOOTSTRAP_CONFIG_TLOG_FILE);

                boolean bootstrapTlogExists = Files.exists(bootstrapTlogPath);
                boolean tlogExists = Files.exists(transactionLogPath);

                IOException tlogValidationError = null;
                if (tlogExists) {
                    try {
                        ConfigurationReader.validateTlog(transactionLogPath);
                    } catch (IOException e) {
                        tlogValidationError = e;
                    }
                }

                // if tlog is present, read the tlog first because the yaml config file may not be up to date
                if (tlogExists && tlogValidationError == null) {
                    kernel.getConfig().read(transactionLogPath);
                }

                // tlog recovery logic if the main tlog isn't valid
                if (tlogValidationError != null) {
                    // Attempt to load from backup tlog file
                    Path backupTlogPath = CommitableFile.getBackupFile(transactionLogPath);
                    boolean backupValid = false;
                    if (Files.exists(backupTlogPath)) {
                        try {
                            ConfigurationReader.validateTlog(backupTlogPath);
                            backupValid = true;
                        } catch (IOException e) {
                            logger.atError().log("Backup transaction log at {} is invalid", backupTlogPath, e);
                        }
                    }

                    if (backupValid) {
                        logger.atError()
                                .log("Transaction log {} is invalid and so is the backup at {}, will attempt to "
                                                + "load configuration from {}", transactionLogPath, backupTlogPath,
                                        bootstrapTlogPath, tlogValidationError);
                        kernel.getConfig().read(backupTlogPath);
                        readFromNonTlog = true;
                    } else if (bootstrapTlogExists) {
                        // If no backup or if the backup was invalid, then try loading from bootstrap
                        logger.atError()
                                .log("Transaction log {} is invalid and no usable backup exists, will attempt to load "
                                                + "configuration from {}", transactionLogPath, bootstrapTlogPath,
                                        tlogValidationError);
                        kernel.getConfig().read(bootstrapTlogPath);
                        readFromNonTlog = true;
                    } else {
                        // There are no files to load from
                        logger.atError()
                                .log("Transaction log {} is invalid and no usable backup exists", transactionLogPath,
                                        tlogValidationError);
                    }
                }

                boolean externalConfigExists = Files.exists(externalConfig);
                // If there is no tlog, or the path was provided via commandline, read in that file
                if ((externalConfigFromCmd || !tlogExists) && externalConfigExists) {
                    kernel.getConfig().read(externalConfig);
                    readFromNonTlog = true;
                }

                // If no bootstrap was present, then write one out now that we've loaded our config so that we can
                // fallback to something
                if (!bootstrapTlogExists) {
                    kernel.writeEffectiveConfigAsTransactionLog(bootstrapTlogPath);
                }
            }

            // write new tlog and config files
            // only dump out the current config if we read from a source which was not the tlog
            if (readFromNonTlog) {
                kernel.writeEffectiveConfigAsTransactionLog(transactionLogPath);
            }
            kernel.writeEffectiveConfig();

            // hook tlog to config so that changes over time are persisted to the tlog
            tlog = ConfigurationWriter.logTransactionsTo(kernel.getConfig(), transactionLogPath)
                    .flushImmediately(true).withAutoTruncate(kernel.getContext());
        } catch (IOException ioe) {
            logger.atError().setEventType("nucleus-read-config-error").setCause(ioe).log();
            throw new RuntimeException(ioe);
        }

    }

    @SuppressWarnings("PMD.CloseResource")
    private Queue<String> findBuiltInServicesAndPlugins() {
        Queue<String> autostart = new LinkedList<>();
        try {
            EZPlugins pim = kernel.getContext().get(EZPlugins.class);
            pim.withCacheDirectory(nucleusPaths.pluginPath());
            pim.annotated(ImplementsService.class, cl -> {
                if (!GreengrassService.class.isAssignableFrom(cl)) {
                    logger.atError().log("{} needs to be a subclass of GreengrassService "
                            + "in order to use ImplementsService", cl);
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
                kernel.getContext().put(Kernel.CONTEXT_SERVICE_IMPLEMENTERS, serviceImplementors);
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
        kernel.orderedDependencies().stream().filter(GreengrassService::shouldAutoStart)
                .forEach(GreengrassService::requestStart);
    }

    /**
     * Shutdown all services in dependency order.
     *
     * @param timeoutSeconds timeout seconds for waiting all services to shutdown. Use -1 to wait infinitely.
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public void stopAllServices(int timeoutSeconds) {
        GreengrassService[] d = kernel.orderedDependencies().toArray(new GreengrassService[0]);

        CompletableFuture<?>[] arr = new CompletableFuture[d.length];
        for (int i = d.length - 1; i >= 0; --i) { // shutdown in reverse order
            String serviceName = d[i].getName();
            try {
                arr[i] = d[i].close();
                arr[i].whenComplete((v, t) -> {
                    if (t != null) {
                        logger.atError("service-shutdown-error", t).kv(GreengrassService.SERVICE_NAME_KEY, serviceName)
                                .log();
                    }
                });
            } catch (Throwable t) {
                logger.atError("service-shutdown-error", t).kv(GreengrassService.SERVICE_NAME_KEY, serviceName).log();
                arr[i] = CompletableFuture.completedFuture(Optional.empty());
            }
        }

        try {
            CompletableFuture<Void> combinedFuture = CompletableFuture.allOf(arr);
            logger.atInfo().log("Waiting for services to shutdown");
            if (timeoutSeconds == -1) {
                combinedFuture.get();
                return;
            }
            combinedFuture.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            List<String> unclosedServices =
                    IntStream.range(0, arr.length).filter((i) -> !arr[i].isDone() || arr[i].isCompletedExceptionally())
                            .mapToObj((i) -> d[i].getName()).collect(Collectors.toList());
            logger.atError("services-shutdown-errored", e).kv("unclosedServices", unclosedServices).log();
        }
    }

    /**
     * Shutdown transaction log and all services with given timeout.
     * @param timeoutSeconds Timeout in seconds
     */
    public void softShutdown(int timeoutSeconds) {
        logger.atDebug("system-shutdown").log("Start soft shutdown");
        kernel.getContext().waitForPublishQueueToClear();
        close(tlog);
        // Update effective config with our last known state
        kernel.writeEffectiveConfig();
        stopAllServices(timeoutSeconds);
    }

    public void shutdown() {
        shutdown(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS);
    }

    /**
     * Shutdown all services and the kernel with given timeout, and exit with the given code.
     *
     * @param timeoutSeconds Timeout in seconds
     * @param exitCode exit code
     */
    @SuppressWarnings("PMD.DoNotCallSystemExit")
    @SuppressFBWarnings("DM_EXIT")
    public void shutdown(int timeoutSeconds, int exitCode) {
        shutdown(timeoutSeconds);
        logger.atInfo("system-shutdown").kv("exitCode", exitCode).log();
        System.exit(exitCode);
    }

    /**
     * Shutdown all services and the kernel with given timeout, but not exit the process.
     *
     * @param timeoutSeconds Timeout in seconds
     */
    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public void shutdown(int timeoutSeconds) {
        if (!isShutdownInitiated.compareAndSet(false, true)) {
            logger.info("Shutdown already initiated, returning...");
            return;
        }
        try {
            logger.atInfo().setEventType("system-shutdown").addKeyValue("main", getMain()).log();
            softShutdown(timeoutSeconds);

            // Do not wait for tasks in the executor to end.
            ScheduledExecutorService scheduledExecutorService = kernel.getContext().get(ScheduledExecutorService.class);
            ExecutorService executorService = kernel.getContext().get(ExecutorService.class);
            kernel.getContext().runOnPublishQueueAndWait(() -> {
                executorService.shutdownNow();
                scheduledExecutorService.shutdownNow();
                logger.atInfo().setEventType("executor-service-shutdown-initiated").log();
            });
            logger.atInfo().log("Waiting for executors to shutdown");
            executorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            scheduledExecutorService.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            logger.atInfo("executor-service-shutdown-complete").log();
            //Stop the telemetry logger context after each test so we can delete the telemetry log files that are
            // created during the test.
            TelemetryConfig.getInstance().closeContext();
            logger.atInfo("context-shutdown-initiated").log();
            kernel.getContext().close();
            logger.atInfo("context-shutdown-complete").log();
        } catch (Throwable ex) {
            logger.atError("system-shutdown-error", ex).log();
        }
        // Stop all the contexts for the loggers.
        LogConfig.getRootLogConfig().closeContext();
        for (LogConfig logConfig : LogManager.getLogConfigurations().values()) {
            logConfig.closeContext();
        }
    }

    GreengrassService getMain() {
        return mainService;
    }
}
