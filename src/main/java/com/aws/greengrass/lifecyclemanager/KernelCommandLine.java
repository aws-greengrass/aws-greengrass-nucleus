/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.DeploymentDirectoryManager;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.bootstrap.BootstrapManager;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.telemetry.impl.config.TelemetryConfig;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.vdurmont.semver4j.Semver;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_JVM_OPTIONS;
import static com.aws.greengrass.lifecyclemanager.GenericExternalService.LIFECYCLE_SCRIPT_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Lifecycle.REQUIRES_PRIVILEGE_NAMESPACE_TOPIC;
import static com.aws.greengrass.util.Utils.HOME_PATH;

public class KernelCommandLine {
    public static final String MAIN_SERVICE_NAME = "main";

    private static final Logger logger = LogManager.getLogger(KernelCommandLine.class);
    private static final String HOME_DIR_PREFIX = "~/";
    private static final String ROOT_DIR_PREFIX = "~root/";
    private static final String CONFIG_DIR_PREFIX = "~config/";
    private static final String PACKAGE_DIR_PREFIX = "~packages/";

    private final Kernel kernel;

    @Getter(AccessLevel.PACKAGE)
    private DeploymentDirectoryManager deploymentDirectoryManager;
    @Getter(AccessLevel.PACKAGE)
    private BootstrapManager bootstrapManager;
    private final NucleusPaths nucleusPaths;

    @Getter
    @Setter(AccessLevel.PACKAGE)
    private String providedConfigPathName;
    private String[] args;
    private String arg;
    private int argpos = 0;

    private String awsRegionFromCmdLine;
    private String envStageFromCmdLine;
    private String defaultUserFromCmdLine;

    private static final String configPathName = "~root/config";
    private static final String workPathName = "~root/work";
    private static final String packageStorePathName = "~root/packages";
    private static final String kernelAltsPathName = "~root/alts";
    private static final String deploymentsPathName = "~root/deployments";
    private static final String cliIpcInfoPathName = "~root/cli_ipc_info";
    private static final String binPathName = "~root/bin";
    private static final String DEFAULT_NUCLEUS_BOOTSTRAP_TEMPLATE = "set -eu%nKERNEL_ROOT=\"%s\"%n"
            + "UNPACK_DIR=\"%s/aws.greengrass.nucleus\"%n"
            + "chmod +x \"$UNPACK_DIR/bin/loader\"%n%n"
            + "rm -r \"$KERNEL_ROOT\"/alts/current/*%n%n"
            + "echo \"%s\" > \"$KERNEL_ROOT/alts/current/launch.params\"%n"
            + "ln -sf \"$UNPACK_DIR\" \"$KERNEL_ROOT/alts/current/distro\"%nexit 100";

    public static void main(String[] args) {
        new Kernel().parseArgs(args).launch();
    }

    public KernelCommandLine(Kernel kernel) {
        this(kernel, kernel.getNucleusPaths());
    }

    KernelCommandLine(Kernel kernel, NucleusPaths nucleusPaths) {
        this.kernel = kernel;
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
                    awsRegionFromCmdLine = getArg();
                    break;
                case "--env-stage":
                case "-es":
                    envStageFromCmdLine = getArg();
                    break;
                case "--component-default-user":
                case "-u":
                    String user = getArg();
                    Objects.requireNonNull(user, "-u or --component-default-user requires an argument");
                    defaultUserFromCmdLine = user;
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }

        if (Utils.isEmpty(rootAbsolutePath)) {
            rootAbsolutePath = "~/.greengrass";  // Default to hidden subdirectory of home.
        }
        rootAbsolutePath = deTilde(rootAbsolutePath);

        kernel.getConfig().lookup("system", "rootpath").dflt(rootAbsolutePath)
                .subscribe((whatHappened, topic) -> initPaths(Coerce.toString(topic)));
    }

    void updateDeviceConfiguration(DeviceConfiguration deviceConfiguration) {
        if (awsRegionFromCmdLine != null) {
            deviceConfiguration.setAWSRegion(awsRegionFromCmdLine);
        }
        if (envStageFromCmdLine != null) {
            deviceConfiguration.getEnvironmentStage().withValue(envStageFromCmdLine);
        }
        if (defaultUserFromCmdLine != null) {
            if (Exec.isWindows) {
                deviceConfiguration.getRunWithDefaultWindowsUser().withValue(defaultUserFromCmdLine);
            } else {
                deviceConfiguration.getRunWithDefaultPosixUser().withValue(defaultUserFromCmdLine);
            }
        }
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
            String storeDirectory = LogManager.getRootLogConfiguration().getStoreDirectory().toAbsolutePath()
                    .toString();
            nucleusPaths.setLoggerPath(Paths.get(storeDirectory));
            nucleusPaths.initPaths(Paths.get(rootAbsolutePath).toAbsolutePath(),
                    Paths.get(deTilde(workPathName)).toAbsolutePath(),
                    Paths.get(deTilde(packageStorePathName)).toAbsolutePath(),
                    Paths.get(deTilde(configPathName)).toAbsolutePath(),
                    Paths.get(deTilde(kernelAltsPathName)).toAbsolutePath(),
                    Paths.get(deTilde(deploymentsPathName)).toAbsolutePath(),
                    Paths.get(deTilde(cliIpcInfoPathName)).toAbsolutePath(),
                    Paths.get(deTilde(binPathName)).toAbsolutePath());

            Exec.setDefaultEnv("HOME", nucleusPaths.workPath().toString());

            // Initialize file and directory managers after kernel root directory is set up
            deploymentDirectoryManager = new DeploymentDirectoryManager(kernel, nucleusPaths);
            kernel.getContext().put(DeploymentDirectoryManager.class, deploymentDirectoryManager);

            // Initialize default nucleus bootstrap script with provided paths
            initNucleusBootstrapScript();
        } catch (IOException e) {
            RuntimeException rte = new RuntimeException("Cannot create all required directories", e);
            logger.atError("system-boot-error", rte).log();
            throw rte;
        }

        // GG_NEEDS_REVIEW (Hui): TODO: Add current kernel to local component store, if not exits.
        // Add symlinks for current Kernel alt, if not exits
        // Register Kernel Loader as system service (platform-specific), if not exits

        bootstrapManager = new BootstrapManager(kernel);
        kernel.getContext().put(BootstrapManager.class, bootstrapManager);
        kernel.getContext().get(KernelAlternatives.class);
    }

    private void initNucleusBootstrapScript() throws IOException {
        // current jvm options. sorted so that the order is consistent
        String jvmOptions = ManagementFactory.getRuntimeMXBean().getInputArguments().stream().sorted()
                .collect(Collectors.joining(" "));
        String rootPath = nucleusPaths.rootPath().toAbsolutePath().toString();
        String unarchivePath = nucleusPaths.unarchiveArtifactPath(
                new ComponentIdentifier(DEFAULT_NUCLEUS_COMPONENT_NAME, new Semver(KernelVersion.KERNEL_VERSION)))
                .toAbsolutePath().toString();
        String bootstrapScript = String.format(DEFAULT_NUCLEUS_BOOTSTRAP_TEMPLATE, rootPath, unarchivePath, jvmOptions);
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY,
                DEVICE_PARAM_JVM_OPTIONS).dflt(jvmOptions);
        kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                        LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, LIFECYCLE_SCRIPT_TOPIC).dflt(bootstrapScript);
        kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, SERVICE_LIFECYCLE_NAMESPACE_TOPIC,
                        LIFECYCLE_BOOTSTRAP_NAMESPACE_TOPIC, REQUIRES_PRIVILEGE_NAMESPACE_TOPIC).dflt(true);
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
