/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.Platform;
import lombok.AccessLevel;
import lombok.Getter;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Objects;

import static com.aws.greengrass.util.Utils.HOME_PATH;

public class KernelCommandLine {
    public static final String MAIN_SERVICE_NAME = "main";

    private static final Logger logger = LogManager.getLogger(KernelCommandLine.class);
    private static final String HOME_DIR_PREFIX = "~/";
    private static final String ROOT_DIR_PREFIX = "~root/";
    private static final String CONFIG_DIR_PREFIX = "~config/";
    private static final String PACKAGE_DIR_PREFIX = "~packages/";

    private final Kernel kernel;
    private final DeviceConfiguration deviceConfiguration;

    @Getter(AccessLevel.PACKAGE)
    private DeploymentDirectoryManager deploymentDirectoryManager;
    @Getter(AccessLevel.PACKAGE)
    private BootstrapManager bootstrapManager;
    private final NucleusPaths nucleusPaths;

    @Getter
    private String providedConfigPathName;
    private String[] args;
    private String arg;
    private int argpos = 0;

    private static final String configPathName = "~root/config";
    private static final String workPathName = "~root/work";
    private static final String packageStorePathName = "~root/packages";
    private static final String kernelAltsPathName = "~root/alts";
    private static final String deploymentsPathName = "~root/deployments";
    private static final String cliIpcInfoPathName = "~root/cli_ipc_info";

    public static void main(String[] args) {
        new Kernel().parseArgs(args).launch();
    }

    public KernelCommandLine(Kernel kernel) {
        this(kernel, kernel.getContext().get(DeviceConfiguration.class), kernel.getNucleusPaths());
    }

    KernelCommandLine(Kernel kernel, DeviceConfiguration deviceConfiguration, NucleusPaths nucleusPaths) {
        this.kernel = kernel;
        this.deviceConfiguration = deviceConfiguration;
        this.nucleusPaths = nucleusPaths;
    }

    /**
     * Parse command line arguments before starting.
     *
     * @param args user-provided arguments
     */
    public void parseArgs(String... args) {
        this.args = args;

        // Get root path from System Property/JVM argument. Default handled after 'while'
        String rootAbsolutePath = System.getProperty("root");

        while (getArg() != null) {
            switch (arg.toLowerCase()) {
                case "--config":
                case "-i":
                    String configArg = getArg();
                    Objects.requireNonNull(configArg, "-i or --config requires an argument");
                    providedConfigPathName = deTilde(configArg);
                    break;
                case "--root":
                case "-r":
                    rootAbsolutePath = getArg();
                    Objects.requireNonNull(rootAbsolutePath, "-r or --root requires an argument");
                    break;
                case "--aws-region":
                case "-ar":
                    deviceConfiguration.setAWSRegion(getArg());
                    break;
                case "--env-stage":
                case "-es":
                    deviceConfiguration.getEnvironmentStage().withValue(getArg());
                    break;
                case "--component-default-user":
                case "-u":
                    if (Exec.isWindows) {
                        deviceConfiguration.getRunWithDefaultWindowsUser().withValue(getArg());
                    } else {
                        deviceConfiguration.getRunWithDefaultPosixUser().withValue(getArg());
                    }
                    break;
                case "--component-default-group":
                case "-g":
                    if (Exec.isWindows) {
                        logger.atWarn().setEventType("parse-args-error").log("group is not used on Windows");
                    } else {
                        deviceConfiguration.getRunWithDefaultPosixGroup().withValue(getArg());
                    }
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }
        try {
            Platform.getInstance().getRunWithGenerator().validateDefaultConfiguration(deviceConfiguration);
        } catch (DeviceConfigurationException e) {
            RuntimeException rte = new RuntimeException(e);
            logger.atError().setEventType("parse-args-error").setCause(rte).log();
            throw rte;
        }

        if (Utils.isEmpty(rootAbsolutePath)) {
            rootAbsolutePath = "~/.greengrass";  // Default to hidden subdirectory of home.
        }
        rootAbsolutePath = deTilde(rootAbsolutePath);

        kernel.getConfig().lookup("system", "rootpath").dflt(rootAbsolutePath)
                .subscribe((whatHappened, topic) -> initPaths(Coerce.toString(topic)));
    }

    private void initPaths(String rootAbsolutePath) {
        // init all paths
        try {
            // Set root path first, so that deTilde works on the subsequent calls
            nucleusPaths.setRootPath(Paths.get(rootAbsolutePath).toAbsolutePath());
            //set root path for the telemetry logger
            TelemetryConfig.getInstance().setRoot(Paths.get(deTilde(ROOT_DIR_PREFIX)));
            LogManager.setRoot(Paths.get(deTilde(ROOT_DIR_PREFIX)));
            nucleusPaths.setTelemetryPath(TelemetryConfig.getInstance().getStoreDirectory());
            nucleusPaths.setLoggerPath(LogManager.getRootLogConfiguration().getStoreDirectory());
            nucleusPaths.initPaths(Paths.get(rootAbsolutePath).toAbsolutePath(),
                    Paths.get(deTilde(workPathName)).toAbsolutePath(),
                    Paths.get(deTilde(packageStorePathName)).toAbsolutePath(),
                    Paths.get(deTilde(configPathName)).toAbsolutePath(),
                    Paths.get(deTilde(kernelAltsPathName)).toAbsolutePath(),
                    Paths.get(deTilde(deploymentsPathName)).toAbsolutePath(),
                    Paths.get(deTilde(cliIpcInfoPathName)).toAbsolutePath());

            Exec.setDefaultEnv("HOME", nucleusPaths.workPath().toString());

            // Initialize file and directory managers after kernel root directory is set up
            deploymentDirectoryManager = new DeploymentDirectoryManager(kernel, nucleusPaths);
            kernel.getContext().put(DeploymentDirectoryManager.class, deploymentDirectoryManager);
        } catch (IOException e) {
            RuntimeException rte = new RuntimeException("Cannot create all required directories", e);
            logger.atError("system-boot-error", rte).log();
            throw rte;
        }

        // GG_NEEDS_REVIEW: TODO: Add current kernel to local component store, if not exits.
        // Add symlinks for current Kernel alt, if not exits
        // Register Kernel Loader as system service (platform-specific), if not exits

        bootstrapManager = new BootstrapManager(kernel);
        kernel.getContext().put(BootstrapManager.class, bootstrapManager);
        kernel.getContext().get(KernelAlternatives.class);
    }

    /**
     * Take a user-provided string which represents a path and resolve it to an absolute path.
     *
     * @param s String to resolve
     * @return resolved path
     */
    public String deTilde(String s) {
        if (s.startsWith(HOME_DIR_PREFIX)) {
            s = HOME_PATH.resolve(s.substring(HOME_DIR_PREFIX.length())).toString();
        }
        if (nucleusPaths == null) {
            return s;
        }
        if (nucleusPaths.rootPath() != null && s.startsWith(ROOT_DIR_PREFIX)) {
            s = nucleusPaths.rootPath().resolve(s.substring(ROOT_DIR_PREFIX.length())).toString();
        }
        if (nucleusPaths.configPath() != null && s.startsWith(CONFIG_DIR_PREFIX)) {
            s = nucleusPaths.configPath().resolve(s.substring(CONFIG_DIR_PREFIX.length())).toString();
        }
        if (nucleusPaths.componentStorePath() != null && s.startsWith(PACKAGE_DIR_PREFIX)) {
            s = nucleusPaths.componentStorePath().resolve(s.substring(PACKAGE_DIR_PREFIX.length())).toString();
        }
        return s;
    }

    @SuppressWarnings("PMD.NullAssignment")
    private String getArg() {
        return arg = args == null || argpos >= args.length ? null : args[argpos++];
    }
}
