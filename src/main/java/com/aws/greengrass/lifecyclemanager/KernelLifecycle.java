/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.componentmanager.plugins.docker.DockerApplicationManagerService;
import com.aws.greengrass.config.ConfigurationReader;
import com.aws.greengrass.config.ConfigurationWriter;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.ipc.IPCEventStreamService;
import com.aws.greengrass.ipc.Startable;
import com.aws.greengrass.ipc.modules.AuthorizationService;
import com.aws.greengrass.ipc.modules.ComponentMetricIPCService;
import com.aws.greengrass.ipc.modules.ConfigStoreIPCService;
import com.aws.greengrass.ipc.modules.LifecycleIPCService;
import com.aws.greengrass.ipc.modules.MqttProxyIPCService;
import com.aws.greengrass.ipc.modules.PubSubIPCService;
import com.aws.greengrass.lifecyclemanager.exceptions.InputValidationException;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.provisioning.DeviceIdentityInterface;
import com.aws.greengrass.provisioning.ProvisionConfiguration;
import com.aws.greengrass.provisioning.ProvisionContext;
import com.aws.greengrass.provisioning.ProvisioningConfigUpdateHelper;
import com.aws.greengrass.provisioning.ProvisioningPluginFactory;
import com.aws.greengrass.provisioning.exceptions.RetryableProvisioningException;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.telemetry.TelemetryAgent;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.tes.TokenExchangeService;
import com.aws.greengrass.util.CommitableFile;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.Utils;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.util.Utils.close;
import static com.aws.greengrass.util.Utils.deepToString;

@SuppressWarnings("PMD.CouplingBetweenObjects")
public class KernelLifecycle {
    private static final Logger logger = LogManager.getLogger(KernelLifecycle.class);
    private static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 30;
    // Enum for provision policy will exist in common library package
    // This will be done as part of re-provisioning
    // TODO:  Use the enum from common library when available
    private static final String DEFAULT_PROVISIONING_POLICY = "PROVISION_IF_NOT_PROVISIONED";
    private static final int MAX_PROVISIONING_PLUGIN_RETRY_ATTEMPTS = 3;

    public static final String MULTIPLE_PROVISIONING_PLUGINS_FOUND_EXCEPTION = "Multiple provisioning plugins found "
            + "[%s]. Greengrass expects only one provisioning plugin";
    public static final String UPDATED_PROVISIONING_MESSAGE = "Updated provisioning configuration";
    private static final List<Class<? extends GreengrassService>> BUILTIN_SERVICES =
            Arrays.asList(DockerApplicationManagerService.class, UpdateSystemPolicyService.class,
                    DeploymentService.class, FleetStatusService.class, TelemetryAgent.class,
                    TokenExchangeService.class);

    private final Kernel kernel;
    private final KernelCommandLine kernelCommandLine;
    private final Map<String, Class<?>> serviceImplementors = new HashMap<>();
    private final NucleusPaths nucleusPaths;
    @Setter (AccessLevel.PACKAGE)
    private ProvisioningConfigUpdateHelper provisioningConfigUpdateHelper;
    @Setter (AccessLevel.PACKAGE)
    private ProvisioningPluginFactory provisioningPluginFactory;
    // setter for unit testing
    @Setter(AccessLevel.PACKAGE)
    private List<Class<? extends Startable>> startables = Arrays.asList(IPCEventStreamService.class,
            AuthorizationService.class, ConfigStoreIPCService.class, LifecycleIPCService.class,
            PubSubIPCService.class, MqttProxyIPCService.class, ComponentMetricIPCService.class);
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
        this.provisioningConfigUpdateHelper = new ProvisioningConfigUpdateHelper(kernel);
        this.provisioningPluginFactory = new ProvisioningPluginFactory();
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

        final List<DeviceIdentityInterface> provisioningPlugins = findProvisioningPlugins();
        // Must be called before everything else so that these are available to be
        // referenced by main/dependencies of main
        final Queue<String> autostart = findBuiltInServicesAndPlugins(); //NOPMD
        loadPlugins();
        // run the provisioning if device is not provisioned
        if (!kernel.getContext().get(DeviceConfiguration.class).isDeviceConfiguredToTalkToCloud()
                && !provisioningPlugins.isEmpty()) {
            // Multiple provisioning plugins may need plugin ordering. We do not support plugin ordering right now
            // There is also no compelling use case right now for multiple provisioning plugins.
            if (provisioningPlugins.size() > 1) {
                String errorString = String.format(MULTIPLE_PROVISIONING_PLUGINS_FOUND_EXCEPTION,
                        provisioningPlugins.toString());
                throw new RuntimeException(errorString);
            }
            executeProvisioningPlugin(provisioningPlugins.get(0));
        }

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

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void executeProvisioningPlugin(DeviceIdentityInterface provisioningPlugin) {
        logger.atDebug().kv("plugin", provisioningPlugin.name()).log("Found provisioning plugin to run");
        RetryUtils.RetryConfig retryConfig = RetryUtils.RetryConfig.builder()
                .maxAttempt(MAX_PROVISIONING_PLUGIN_RETRY_ATTEMPTS)
                .retryableExceptions(Collections.singletonList(RetryableProvisioningException.class))
                .build();
        ExecutorService executorService = kernel.getContext().get(ExecutorService.class);
        executorService.execute(() -> {
            String pluginName = provisioningPlugin.name();
            logger.atInfo().log("Running provisioning plugin: " + pluginName);
            Topics pluginConfig = kernel.getConfig()
                    .findTopics(SERVICES_NAMESPACE_TOPIC, pluginName, CONFIGURATION_CONFIG_KEY);
            ProvisionConfiguration provisionConfiguration = null;
            try {
                provisionConfiguration = RetryUtils.runWithRetry(retryConfig,
                        () -> provisioningPlugin.updateIdentityConfiguration(new ProvisionContext(
                                DEFAULT_PROVISIONING_POLICY, pluginConfig == null
                                ? Collections.emptyMap() : pluginConfig.toPOJO())),
                        "Running provisioning plugin", logger);
            } catch (Exception e) {
                logger.atError().setCause(e).log("Caught exception while running provisioning plugin. "
                        + "Moving on to run Greengrass without provisioning");
                return;
            }

            provisioningConfigUpdateHelper.updateSystemConfiguration(provisionConfiguration
                    .getSystemConfiguration(), UpdateBehaviorTree.UpdateBehavior.MERGE);
            provisioningConfigUpdateHelper.updateNucleusConfiguration(provisionConfiguration
                    .getNucleusConfiguration(), UpdateBehaviorTree.UpdateBehavior.MERGE);
            logger.atDebug().kv("PluginName", pluginName)
                    .log(UPDATED_PROVISIONING_MESSAGE);
        });
    }

    @SuppressWarnings("PMD.CloseResource")
    private List<DeviceIdentityInterface> findProvisioningPlugins() {
        List<DeviceIdentityInterface> provisioningPlugins = new ArrayList<>();
        Set<String> provisioningPluginNames = new HashSet<>();
        EZPlugins ezPlugins = kernel.getContext().get(EZPlugins.class);
        try {
            ezPlugins.withCacheDirectory(nucleusPaths.pluginPath());
            ezPlugins.implementing(DeviceIdentityInterface.class, (c) -> {
                try {
                    if (!provisioningPluginNames.contains(c.getName())) {
                        provisioningPlugins.add(provisioningPluginFactory.getPluginInstance(c));
                        provisioningPluginNames.add(c.getName());
                    }
                } catch (InstantiationException | IllegalAccessException e) {
                    logger.atError().kv("Plugin", c.getName()).setCause(e)
                            .log("Error instantiating a provisioning plugin");
                }
            });
        } catch (IOException t) {
            logger.atError().log("Error finding provisioning plugins", t);
        }
        return provisioningPlugins;
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
                if (is.autostart() && !autostart.contains(is.name())) {
                    autostart.add(is.name());
                }
                serviceImplementors.put(is.name(), cl);
                logger.atInfo().log("Found Plugin: {}", cl.getSimpleName());
            });
        } catch (IOException t) {
            logger.atError().log("Error finding built in service plugins", t);
        }

        for (Class<? extends GreengrassService> cl : BUILTIN_SERVICES) {
            ImplementsService is = cl.getAnnotation(ImplementsService.class);
            if (is.autostart() && !autostart.contains(is.name())) {
                autostart.add(is.name());
            }
            serviceImplementors.put(is.name(), cl);
        }

        return autostart;
    }

    @SuppressWarnings("PMD.CloseResource")
    private void loadPlugins() {
        EZPlugins pim = kernel.getContext().get(EZPlugins.class);
        try {
            // For integration testing of plugins, scan our own classpath to find the @ImplementsService
            if ("true".equals(System.getProperty("aws.greengrass.scanSelfClasspath"))) {
                pim.scanSelfClasspath();
            }
            pim.loadCache();
            if (!serviceImplementors.isEmpty()) {
                kernel.getContext().put(Kernel.CONTEXT_SERVICE_IMPLEMENTERS, serviceImplementors);
            }
            logger.atInfo().log("serviceImplementors: {}", deepToString(serviceImplementors));
        } catch (IOException e) {
            logger.atError().log("Error launching plugins", e);
        }
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
            boolean executorTerminated = executorService.awaitTermination(timeoutSeconds,
                    TimeUnit.SECONDS);
            boolean scheduledExecutorTerminated = scheduledExecutorService.awaitTermination(timeoutSeconds,
                    TimeUnit.SECONDS);
            logger.atInfo("executor-service-shutdown-complete")
                    .kv("executor-terminated", executorTerminated)
                    .kv("scheduled-executor-terminated", scheduledExecutorTerminated).log();
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
