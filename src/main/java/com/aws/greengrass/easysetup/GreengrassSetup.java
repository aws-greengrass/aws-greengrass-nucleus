/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.EZPlugins;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
import com.aws.greengrass.lifecyclemanager.KernelLifecycle;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.tes.TokenExchangeService;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import com.aws.greengrass.util.orchestration.SystemServiceUtilsFactory;
import com.aws.greengrass.util.platforms.Platform;
import lombok.Setter;
import software.amazon.awssdk.regions.Region;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.aws.greengrass.easysetup.DeviceProvisioningHelper.ThingInfo;

/**
 * Easy setup for getting started with Greengrass kernel on a device.
 */
public class GreengrassSetup {
    private static final String SHOW_HELP_RESPONSE = "DESCRIPTION\n"
            + "\tInstall the Greengrass Nucleus, (optional) install local development tools, and (optional)\n"
            + "\tregister your device as an AWS IoT thing. This creates device certificates, attaches a role\n"
            + "\tto use the AWS IoT credentials provider, and creates a role that provides AWS credentials.\n"
            + "\n"
            + "OPTIONS\n"
            + "\t--help, -h\t\t\t(Optional) Show this help information and then exit.\n"
            + "\t--version\t\t\t(Optional) Show the version of the AWS IoT Greengrass Core software, and then exit.\n"
            + "\t--aws-region, -ar\t\t\tThe AWS Region to use. The AWS IoT Greengrass Core software uses this Region\n"
            + "\t\t\t\t\t to retrieve or create the AWS resources that it requires\n"
            + "\t--root, -r\t\t\t(Optional) The path to the folder to use as the root for the AWS IoT Greengrass Core\n"
            + "\t\t\t\t\tsoftware. Defaults to ~/.greengrass.\n"
            + "\t--init-config, -init\t\t\t(Optional) The path to the configuration file that you use to run the AWS "
            + "IoT Greengrass Core software.\n"
            + "\t\t\t\t\tsoftware. Defaults to ~/.greengrass.\n"
            + "\t--provision, -p\t\t\t(Optional) Specify true or false. If true, the AWS IoT Greengrass Core software"
            + " registers this\n"
            + "\t\t\t\t\tdevice as an AWS IoT thing, and provisions the AWS resources that the software requires. The\n"
            + "\t\t\t\t\tsoftware provisions an AWS IoT thing, (optional) an AWS IoT thing group, a Thing Policy, an \n"
            + "\t\t\t\t\tIAM role, and an AWS IoT role alias. Defaults to false.\n"
            + "\t--thing-name, -tn\t\t(Optional) The name of the AWS IoT thing that you register as this core device."
            + " If the thing with\n"
            + "\t\t\t\t\tthis name doesn't exist in your AWS account, the AWS IoT Greengrass Core software creates it."
            + "\n\t\t\t\t\tDefaults to GreengrassV2IotThing_ plus a random UUID.\n"
            + "\t--thing-group-name, -tgn\t(Optional) The name of the AWS IoT thing group where you add this core "
            + "device's AWS IoT thing. \n"
            + "\t\t\t\t\tIf a deployment targets this thing group, this core device receives that deployment when it\n"
            + "\t\t\t\t\tconnects to AWS IoT Greengrass. If the thing group with this name doesn't exist in your AWS\n"
            + "\t\t\t\t\taccount, the AWS IoT Greengrass Core software creates it. Defaults to no thing group.\n"
            + "\t--thing-policy-name, -tpn\t(Optional) The name of the AWS IoT Policy to attach to the core device's "
            + "AWS IoT thing. \n"
            + "\t\t\t\t\tIf specified, then the supplied thing-policy-name is attached to the provisioned IoT Thing.\n"
            + "\t\t\t\t\tOtherwise a policy called GreengrassV2IoTThingPolicy is used instead. If the policy with\n"
            + "\t\t\t\t\tthis name doesn't exist in your AWS account, the AWS IoT Greengrass Core software creates it\n"
            + "\t\t\t\t\twith a default policy document.\n"
            + "\t—tes-role-name, -trn\t\t(Optional) The name of the IAM role to use to acquire AWS credentials that "
            + "let the device\n"
            + "\t\t\t\t\tinteract with AWS services. If the role with this name doesn't exist in your AWS account, "
            + "the AWS\n"
            + "\t\t\t\t\tIoT Greengrass Core software creates it with the GreengrassV2TokenExchangeRoleAccess policy.\n"
            + "\t\t\t\t\tThis role doesn't have access to your S3 buckets where you host component artifacts. So, you\n"
            + "\t\t\t\t\tmust add permissions to your artifacts' S3 buckets and objects when you create a component.\n"
            + "\t\t\t\t\tDefaults to GreengrassV2TokenExchangeRole.\n"
            + "\t--tes-role-alias-name, -tra\t(Optional) The name of the AWS IoT role alias that points to the IAM "
            + "role that provides AWS\n"
            + "\t\t\t\t\tcredentials for this device. If the role alias with this name doesn't exist in your AWS "
            + "account, the\n"
            + "\t\t\t\t\tAWS IoT Greengrass Core software creates it and points it to the IAM role that you specify.\n"
            + "\t\t\t\t\tDefaults to GreengrassV2TokenExchangeRoleAlias.\n"
            + "\t--setup-system-service, -ss\t(Optional) Specify true or false. If true, then the AWS IoT Greengrass "
            + "Core software sets\n"
            + "\t\t\t\t\titself up as a system service that runs when this device boots. The system service name is "
            + "greengrass.\n"
            + "\t\t\t\t\tDefaults to false.\n"
            + "\t--component-default-user, -u\t(Optional) The name of ID of the system user and group that the AWS "
            + "IoT Greengrass Core\n"
            + "\t\t\t\t\tsoftware uses to run components. This argument accepts the user and group separated by a\n"
            + "\t\t\t\t\tcolon, where the group is optional. For example, you can specify ggc_user:ggc_group or\n"
            + "\t\t\t\t\tggc_user.\n\n"
            + "\t\t\t\t\t* If you run as root, this defaults to the user and group that the config file defines. If "
            + "the config \n"
            + "\t\t\t\t\tfile doesn't define a user and group, this defaults to ggc_user:ggc_group. If ggc_user or\n"
            + "\t\t\t\t\tggc_group don't exist, the software creates them.\n\n"
            + "\t\t\t\t\t* If you run as a non-root user, the AWS IoT Greengrass Core software uses that user to run "
            + "components.\n\n"
            + "\t\t\t\t\t* If you don't specify a group, the AWS IoT Greengrass Core software uses the primary group \n"
            + "\t\t\t\t\tof the system user\n"
            + "\n\t--deploy-dev-tools, -d\t\t(Optional) Specify true or false. If true, the AWS IoT Greengrass Core "
            + "software retrieves and\n"
            + "\t\t\t\t\tdeploys the Greengrass CLI component. Specify true to set up this core\n"
            + "\t\t\t\t\tdevice for local development. Specify false to set up this core device in a production\n"
            + "\t\t\t\t\tenvironment. Defaults to false.\n"
            + "\n\t--start, -s\t\t\t(Optional) Specify true or false. If true, the AWS IoT Greengrass Core software "
            + "runs setup steps,\n"
            + "\t\t\t\t\t(optional) provisions resources, and starts the software. If false, the software runs only "
            + "setup\n"
            + "\t\t\t\t\tsteps and (optional) provisions resources. Defaults to true.\n"
            + "\n\t--trusted-plugin, -tp\t\t(Optional) Path of a plugin jar file. The plugin will be included as "
            + "trusted plugin in nucleus. Specify multiple times for including multiple plugins.\n";

    private static final String SHOW_VERSION_RESPONSE = "AWS Greengrass v%s";

    private static final String HELP_ARG = "--help";
    private static final String HELP_ARG_SHORT = "-h";

    private static final String KERNEL_CONFIG_ARG = "--config";
    private static final String KERNEL_CONFIG_ARG_SHORT = "-i";
    private static final String KERNEL_INIT_CONFIG_ARG = "--init-config";
    private static final String KERNEL_INIT_CONFIG_ARG_SHORT = "-init";
    private static final String KERNEL_ROOT_ARG = "--root";
    private static final String KERNEL_ROOT_ARG_SHORT = "-r";

    private static final String DEFAULT_USER_ARG = "--component-default-user";
    private static final String DEFAULT_USER_ARG_SHORT = "-u";

    private static final String THING_NAME_ARG = "--thing-name";
    private static final String THING_NAME_ARG_SHORT = "-tn";
    private static final String THING_NAME_DEFAULT = "GreengrassV2IotThing_" + UUID.randomUUID();

    private static final String THING_GROUP_NAME_ARG = "--thing-group-name";
    private static final String THING_GROUP_NAME_ARG_SHORT = "-tgn";

    private static final String THING_POLICY_NAME_ARG = "--thing-policy-name";
    private static final String THING_POLICY_NAME_ARG_SHORT = "-tpn";
    private static final String THING_POLICY_NAME_DEFAULT = "GreengrassV2IoTThingPolicy";

    private static final String TES_ROLE_NAME_ARG = "--tes-role-name";
    private static final String TES_ROLE_NAME_ARG_SHORT = "-trn";
    private static final String TES_ROLE_NAME_DEFAULT = "GreengrassV2TokenExchangeRole";

    private static final String TES_ROLE_ALIAS_NAME_ARG = "--tes-role-alias-name";
    private static final String TES_ROLE_ALIAS_NAME_ARG_SHORT = "-tra";
    private static final String TES_ROLE_ALIAS_NAME_DEFAULT = "GreengrassV2TokenExchangeRoleAlias";

    private static final String PROVISION_THING_ARG = "--provision";
    private static final String PROVISION_THING_ARG_SHORT = "-p";
    private static final boolean NEED_PROVISIONING_DEFAULT = false;

    private static final String AWS_REGION_ARG = "--aws-region";
    private static final String AWS_REGION_ARG_SHORT = "-ar";

    private static final String ENV_STAGE_ARG = "--env-stage";
    private static final String ENV_STAGE_ARG_SHORT = "-es";
    private static final String ENV_STAGE_DEFAULT = "prod";

    private static final String SETUP_SYSTEM_SERVICE_ARG = "--setup-system-service";
    private static final String SETUP_SYSTEM_SERVICE_ARG_SHORT = "-ss";
    private static final boolean SETUP_SYSTEM_SERVICE_ARG_DEFAULT = false;

    private static final String KERNEL_START_ARG = "--start";
    private static final String KERNEL_START_ARG_SHORT = "-s";
    private static final boolean KERNEL_START_ARG_DEFAULT = true;

    private static final String DEPLOY_DEV_TOOLS_ARG = "--deploy-dev-tools";
    private static final String DEPLOY_DEV_TOOLS_ARG_SHORT = "-d";
    private static final boolean DEPLOY_DEV_TOOLS_ARG_DEFAULT = false;

    private static final String VERSION_ARG = "--version";
    private static final String VERSION_ARG_SHORT = "-v";

    private static final String TRUSTED_PLUGIN_ARG = "--trusted-plugin";
    private static final String TRUSTED_PLUGIN_ARG_SHORT = "-tp";

    private static final String GGC_USER = "ggc_user";
    private static final String GGC_GROUP = "ggc_group";
    private static final String DEFAULT_POSIX_USER = String.format("%s:%s", GGC_USER, GGC_GROUP);

    private static final Logger logger = LogManager.getLogger(GreengrassSetup.class);
    private static final String TRUSTED_PLUGIN_PATH_NON_JAR_ERROR
            = "The trusted plugin path should point to a jar file";
    private static final String TRUSTED_PLUGIN_JAR_DOES_NOT_EXIST
            = "The trusted plugin jar file does not exist or is not accessible";
    private final String[] setupArgs;
    private final List<String> kernelArgs = new ArrayList<>();
    @Setter
    private DeviceProvisioningHelper deviceProvisioningHelper;
    private final PrintStream outStream;
    private final PrintStream errStream;
    private int argpos = 0;
    private String arg;
    private boolean showHelp = false;
    private boolean showVersion = false;
    private String thingName = THING_NAME_DEFAULT;
    private String thingGroupName;
    private String thingPolicyName = THING_POLICY_NAME_DEFAULT;
    private String tesRoleName = TES_ROLE_NAME_DEFAULT;
    private String tesRoleAliasName = TES_ROLE_ALIAS_NAME_DEFAULT;
    private String awsRegion;
    private String environmentStage = ENV_STAGE_DEFAULT;
    private String defaultUser;
    private boolean needProvisioning = NEED_PROVISIONING_DEFAULT;
    private boolean setupSystemService = SETUP_SYSTEM_SERVICE_ARG_DEFAULT;
    private boolean kernelStart = KERNEL_START_ARG_DEFAULT;
    private boolean deployDevTools = DEPLOY_DEV_TOOLS_ARG_DEFAULT;
    private Platform platform;
    private Kernel kernel;
    private List<String> trustedPluginPaths;

    /**
     * Constructor to create an instance using CLI args.
     *
     * @param outStream writer to use to send text response to user
     * @param errStream writer to use to send error response to user
     * @param setupArgs CLI args for setup script
     */
    public GreengrassSetup(PrintStream outStream, PrintStream errStream, String... setupArgs) {
        this.setupArgs = setupArgs;
        this.outStream = outStream;
        this.errStream = errStream;
    }

    /**
     * Constructor for unit tests.
     *
     * @param outStream                writer to use to send text response to user
     * @param errStream                writer to use to send error response to user
     * @param deviceProvisioningHelper Prebuilt DeviceProvisioningHelper instance
     * @param platform                 a platform to use
     * @param kernel                   a kernel instance
     * @param setupArgs                CLI args for setup script
     */
    GreengrassSetup(PrintStream outStream, PrintStream errStream, DeviceProvisioningHelper deviceProvisioningHelper,
                    Platform platform, Kernel kernel, String... setupArgs) {
        this.setupArgs = setupArgs;
        this.outStream = outStream;
        this.errStream = errStream;
        this.deviceProvisioningHelper = deviceProvisioningHelper;
        this.kernel = kernel;
        this.platform = platform;
    }

    /**
     * Entry point for setup script.
     *
     * @param args CLI args for setup script
     * @throws Exception error in setup
     */
    @SuppressWarnings(
            {"PMD.NullAssignment", "PMD.AvoidCatchingThrowable", "PMD.DoNotCallSystemExit", "PMD.SystemPrintln"})
    public static void main(String[] args) {
        GreengrassSetup greengrassSetup = new GreengrassSetup(System.out, System.err, args);
        try {
            greengrassSetup.parseArgs();
            greengrassSetup.performSetup();
        } catch (Throwable t) {
            logger.atError().setCause(t).log("Error while trying to setup Greengrass Nucleus");
            System.err.println("Error while trying to setup Greengrass Nucleus");
            t.printStackTrace(greengrassSetup.errStream);
            System.exit(1);
        }
    }

    void performSetup() throws IOException, DeviceConfigurationException, URISyntaxException,
            InvalidEnvironmentStageException {
        // Describe usage of the command
        if (showHelp) {
            outStream.println(SHOW_HELP_RESPONSE);
            return;
        }
        if (showVersion) {
            // Use getVersionFromBuildMetadataFile so that we don't need to startup the Nucleus which is slow and will
            // start creating files and directories which may not be desired
            outStream.println(String.format(SHOW_VERSION_RESPONSE,
                    DeviceConfiguration.getVersionFromBuildRecipeFile()));
            return;
        }

        if (kernel == null) {
            kernel = new Kernel();
        }
        kernel.parseArgs(kernelArgs.toArray(new String[]{}));

        try {
            IotSdkClientFactory.EnvironmentStage.fromString(environmentStage);
        } catch (InvalidEnvironmentStageException e) {
            throw new RuntimeException(e);
        }

        if (!Utils.isEmpty(trustedPluginPaths)) {
            copyTrustedPlugins(kernel, trustedPluginPaths);
        }

        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        if (needProvisioning) {
            if (Utils.isEmpty(awsRegion)) {
                awsRegion = Coerce.toString(deviceConfiguration.getAWSRegion());
            }

            if (Utils.isEmpty(awsRegion)) {
                throw new RuntimeException("Required input aws region not provided for provisioning");
            }

            this.deviceProvisioningHelper = new DeviceProvisioningHelper(awsRegion, environmentStage, this.outStream);
            provision(kernel);
        }

        // Attempt this only after config file and Nucleus args have been parsed
        setComponentDefaultUserAndGroup(deviceConfiguration);

        if (setupSystemService) {
            kernel.getContext().get(KernelLifecycle.class).softShutdown(30);
            boolean ok = kernel.getContext().get(SystemServiceUtilsFactory.class).getInstance()
                    .setupSystemService(kernel.getContext().get(KernelAlternatives.class));
            if (ok) {
                outStream.println("Successfully set up Nucleus as a system service");
                // Nucleus will be launched by OS as a service
            } else {
                outStream.println("Unable to set up Nucleus as a system service");
            }
            kernel.shutdown();
            return;
        }
        if (!kernelStart) {
            outStream.println("Nucleus start set to false, exiting...");
            kernel.shutdown();
            return;
        }
        outStream.println("Launching Nucleus...");
        kernel.launch();
        outStream.println("Launched Nucleus successfully.");
    }

    private void copyTrustedPlugins(Kernel kernel, List<String> trustedPluginPaths) {
        Path trustedPluginPath;
        try {
            trustedPluginPath = kernel.getContext().get(EZPlugins.class)
                    .withCacheDirectory(kernel.getNucleusPaths().pluginPath())
                    .getTrustedCacheDirectory();
        } catch (IOException e) {
            logger.atError().setCause(e)
                    .log("Caught exception while getting trusted plugins directory path");
            throw new RuntimeException(e);
        }
        trustedPluginPaths.forEach(pluginPath -> {
            try {
                Files.copy(Paths.get(pluginPath), trustedPluginPath.resolve(Utils.namePart(pluginPath)),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                logger.atError().kv("pluginPath", pluginPath).setCause(e)
                        .log("Caught exception while copying plugin jar to trusted plugins directory");
                throw new RuntimeException(e);
            }
        });
    }

    void parseArgs() {
        loop: while (getArg() != null) {
            switch (arg.toLowerCase()) {
                case HELP_ARG:
                case HELP_ARG_SHORT:
                    this.showHelp = true;
                    break loop;
                case VERSION_ARG:
                case VERSION_ARG_SHORT:
                    this.showVersion = true;
                    break loop;
                case KERNEL_CONFIG_ARG:
                case KERNEL_CONFIG_ARG_SHORT:
                case KERNEL_ROOT_ARG:
                case KERNEL_ROOT_ARG_SHORT:
                case KERNEL_INIT_CONFIG_ARG:
                case KERNEL_INIT_CONFIG_ARG_SHORT:
                    kernelArgs.add(arg);
                    kernelArgs.add(getArg());
                    break;
                case THING_NAME_ARG:
                case THING_NAME_ARG_SHORT:
                    this.thingName = getArg();
                    break;
                case THING_GROUP_NAME_ARG:
                case THING_GROUP_NAME_ARG_SHORT:
                    this.thingGroupName = getArg();
                    break;
                case THING_POLICY_NAME_ARG:
                case THING_POLICY_NAME_ARG_SHORT:
                    this.thingPolicyName = getArg();
                    break;
                case TES_ROLE_NAME_ARG:
                case TES_ROLE_NAME_ARG_SHORT:
                    this.tesRoleName = getArg();
                    break;
                case TES_ROLE_ALIAS_NAME_ARG:
                case TES_ROLE_ALIAS_NAME_ARG_SHORT:
                    this.tesRoleAliasName = getArg();
                    break;
                case AWS_REGION_ARG:
                case AWS_REGION_ARG_SHORT:
                    kernelArgs.add(arg);
                    this.awsRegion = getArg();
                    if (!Region.regions().contains(Region.of(awsRegion))) {
                        throw new RuntimeException(String.format("%s is invalid AWS region", awsRegion));
                    }
                    kernelArgs.add(awsRegion);
                    break;

                case ENV_STAGE_ARG:
                case ENV_STAGE_ARG_SHORT:
                    kernelArgs.add(arg);
                    this.environmentStage = getArg();
                    kernelArgs.add(environmentStage.toLowerCase());
                    break;
                case PROVISION_THING_ARG:
                case PROVISION_THING_ARG_SHORT:
                    this.needProvisioning = Coerce.toBoolean(getArg());
                    break;
                case SETUP_SYSTEM_SERVICE_ARG:
                case SETUP_SYSTEM_SERVICE_ARG_SHORT:
                    this.setupSystemService = Coerce.toBoolean(getArg());
                    break;
                case KERNEL_START_ARG:
                case KERNEL_START_ARG_SHORT:
                    this.kernelStart = Coerce.toBoolean(getArg());
                    break;
                case DEFAULT_USER_ARG:
                case DEFAULT_USER_ARG_SHORT:
                    String argument = arg;
                    kernelArgs.add(argument);
                    this.defaultUser = Coerce.toString(getArg());
                    if (Utils.isEmpty(defaultUser)) {
                        throw new RuntimeException(String.format("No user specified with %s", argument));
                    }
                    kernelArgs.add(defaultUser);
                    break;
                case DEPLOY_DEV_TOOLS_ARG:
                case DEPLOY_DEV_TOOLS_ARG_SHORT:
                    this.deployDevTools = Coerce.toBoolean(getArg());
                    break;
                case TRUSTED_PLUGIN_ARG:
                case TRUSTED_PLUGIN_ARG_SHORT:
                    String pluginJarPath = Coerce.toString(getArg());
                    validatePluginJarPath(pluginJarPath);
                    if (trustedPluginPaths == null) {
                        trustedPluginPaths = new ArrayList<>();
                    }
                    trustedPluginPaths.add(pluginJarPath);
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }
    }

    private void validatePluginJarPath(String pluginJarPath) {
        String nm = Utils.namePart(pluginJarPath);
        if (!nm.endsWith(EZPlugins.JAR_FILE_EXTENSION)) {
            throw new RuntimeException(TRUSTED_PLUGIN_PATH_NON_JAR_ERROR);
        }
        File pluginFile = new File(pluginJarPath);
        if (!pluginFile.exists()) {
            throw new RuntimeException(TRUSTED_PLUGIN_JAR_DOES_NOT_EXIST);
        }
        // Not validating permissions as it may be os dependent and permissions failure will come as IOException
        // which will be thrown as RuntimeException when copying the plugin jar.
    }

    @SuppressWarnings("PMD.NullAssignment")
    private String getArg() {
        return arg = setupArgs == null || argpos >= setupArgs.length ? null : setupArgs[argpos++];
    }

    void provision(Kernel kernel) throws IOException, DeviceConfigurationException {
        outStream.printf("Provisioning AWS IoT resources for the device with IoT Thing Name: [%s]...%n", thingName);
        final ThingInfo thingInfo =
                deviceProvisioningHelper.createThing(deviceProvisioningHelper.getIotClient(), thingPolicyName,
                        thingName);
        outStream.printf("Successfully provisioned AWS IoT resources for the device with IoT Thing Name: [%s]!%n",
                thingName);
        if (!Utils.isEmpty(thingGroupName)) {
            outStream.printf("Adding IoT Thing [%s] into Thing Group: [%s]...%n", thingName, thingGroupName);
            deviceProvisioningHelper
                    .addThingToGroup(deviceProvisioningHelper.getIotClient(), thingName, thingGroupName);
            outStream.printf("Successfully added Thing into Thing Group: [%s]%n", thingGroupName);
        }
        outStream.printf("Setting up resources for %s ... %n", TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS);
        deviceProvisioningHelper.setupIoTRoleForTes(tesRoleName, tesRoleAliasName, thingInfo.getCertificateArn());
        deviceProvisioningHelper.createAndAttachRolePolicy(tesRoleName, Region.of(awsRegion));
        outStream.println("Configuring Nucleus with provisioned resource details...");
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, awsRegion, tesRoleAliasName);
        outStream.println("Successfully configured Nucleus with provisioned resource details!");
        if (deployDevTools) {
            deviceProvisioningHelper.createInitialDeploymentIfNeeded(thingInfo, thingGroupName,
                    kernel.getContext().get(DeviceConfiguration.class).getNucleusVersion());
        }

        // Dump config since we've just provisioned so that the bootstrap config will enable us to
        // reach the cloud when needed. Must do this now because we normally would never overwrite the bootstrap
        // file, however we need to do it since we've only just learned about our endpoints, certs, etc.
        kernel.writeEffectiveConfigAsTransactionLog(kernel.getNucleusPaths().configPath()
                .resolve(Kernel.DEFAULT_BOOTSTRAP_CONFIG_TLOG_FILE));
    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private void setComponentDefaultUserAndGroup(DeviceConfiguration deviceConfiguration) {
        if (PlatformResolver.isWindows) {
            outStream.println("Default user creation is only supported on Linux platforms, Greengrass will not make a"
                    + " user for you. Ensure that the user exists and password is stored. Continuing...");
            return;
        }
        try {
            if (platform == null) {
                platform = Platform.getInstance();
            }
            // If not super user we cannot create anything
            if (!platform.lookupCurrentUser().isSuperUser()) {
                return;
            }

            // If user was given as cli input it has been added to the config by now.
            Topic defaultUserTopic = deviceConfiguration.getRunWithDefaultPosixUser();
            defaultUser = Coerce.toString(defaultUserTopic);
            boolean noDefaultSet = Utils.isEmpty(defaultUser);

            boolean setGGCUser = noDefaultSet;
            boolean setGGCGroup = noDefaultSet;
            if (noDefaultSet) {
                outStream.printf("No input for component default user, using %s:%s %n", GGC_USER, GGC_GROUP);
            } else {
                String[] userGroup = defaultUser.split(":", 2);
                setGGCUser = GGC_USER.equals(userGroup[0]);
                if (userGroup.length > 1) {
                    setGGCGroup = GGC_GROUP.equals(userGroup[1]);
                }
                if (Utils.isEmpty(userGroup[0])) {
                    throw new RuntimeException("No user specified");
                }
            }
            if (setGGCUser) {
                boolean updateGGCGroup = false;
                if (!platform.userExists(GGC_USER)) {
                    outStream.printf("Creating user %s %n", GGC_USER);
                    platform.createUser(GGC_USER);
                    outStream.printf("%s created %n", GGC_USER);
                    updateGGCGroup = true;
                }
                if (setGGCGroup) {
                    try {
                        platform.lookupGroupByName(GGC_GROUP);
                    } catch (IOException e) {
                        outStream.printf("Creating group %s %n", GGC_GROUP);
                        platform.createGroup(GGC_GROUP);
                        outStream.printf("%s created %n", GGC_GROUP);
                        updateGGCGroup = true;
                    }
                    if (updateGGCGroup) {
                        platform.addUserToGroup(GGC_USER, GGC_GROUP);
                        outStream.printf("Added %s to %s %n", GGC_USER, GGC_GROUP);
                    }
                }
            }
            if (noDefaultSet) {
                defaultUserTopic.withValue(DEFAULT_POSIX_USER);
            }
        } catch (IOException e) {
            throw new RuntimeException("Error setting up component default user / group", e);
        }
    }

}
