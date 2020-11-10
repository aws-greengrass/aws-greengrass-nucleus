/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.KernelAlternatives;
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

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.aws.greengrass.easysetup.DeviceProvisioningHelper.ThingInfo;
import static com.aws.greengrass.lifecyclemanager.KernelVersion.KERNEL_VERSION;

/**
 * Easy setup for getting started with Greengrass kernel on a device.
 */
public class GreengrassSetup {
    // GG_REL_BLOCKER: TODO: [P41215451]: Review with tech writer prior to launch (including TES)
    private static final String SHOW_HELP_RESPONSE = "DESCRIPTION\n"
            + "\tInstall Greengrass kernel on your device, register the device as IoT thing,\n"
            + "\tcreate certificates and attach role for TES to them, install the Greengrass\n"
            + "\tdevice CLI. Please set the AWS credentials in the environment variables\n"
            + "\n"
            + "OPTIONS\n"
            + "\t--config, -i\t\t\tPath to the configuration file to start Greengrass kernel with\n"
            + "\t--root, -r\t\t\tPath to the directory to use as the root for Greengrass\n"
            + "\t--thing-name, -tn\t\tDesired thing name to register the device with in AWS IoT cloud\n"
            + "\t--thing-group-name, -tgn\tDesired thing group to add the IoT Thing into\n"
            + "\t—tes-role-name, -trn\t\tName of the IAM role to use for "
            + TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS
            + "\n\t\t\t\t\tdevice to talk to AWS services, the default is GreengrassV2TokenExchangeRole.\n"
            + "\t\t\t\t\tIf the role does not exist in your account it will be created with a default policy\n"
            + "\t\t\t\t\tcalled GreengrassV2TokenExchangeRoleAccess, by default it DOES NOT have access\n"
            + "\t\t\t\t\tto your S3 buckets where you will host your private component artifacts, so you need\n"
            + "\t\t\t\t\tto add your component artifact S3 buckets/objects to that role in your AWS account.\n"
            + "\t--tes-role-alias-name, -tra\tName of the RoleAlias to attach to the IAM role for\n"
            + "\t\t\t\t\t"
            + TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS
            + "in the AWS IoT cloud if the role\n"
            + "\t\t\t\t\talias does not exist then it will be created in your AWS account\n"
            + "\t--provision, -p\t\t\t/N Indicate if you want to register the device as an AWS IoT thing\n"
            + "\t--aws-region, -ar\t\tAWS region where the resources should be looked for/created\n"
            + "\t\t\t\t\tCorresponding Iot environment stage will be used.\n"
            + "\t--setup-system-service, -ss\tY/N Indicate if you want to setup Greengrass as a system service\n"
            + "\t--component-default-user, -u\tName of the default user that will be used to run component services\n"
            + "\t--component-default-group, -g\tName of the default group that will be used to run component services."
            + "\n\t\t\t\t\tIf not specified the primary group of the default user will be used.\n";

    private static final String SHOW_VERSION_RESPONSE = "AWS Greengrass v%s";

    private static final String HELP_ARG = "--help";
    private static final String HELP_ARG_SHORT = "-h";

    private static final String KERNEL_CONFIG_ARG = "--config";
    private static final String KERNEL_CONFIG_ARG_SHORT = "-i";
    private static final String KERNEL_ROOT_ARG = "--root";
    private static final String KERNEL_ROOT_ARG_SHORT = "-r";

    private static final String DEFAULT_USER_ARG = "--component-default-user";
    private static final String DEFAULT_USER_ARG_SHORT = "-u";

    private static final String DEFAULT_GROUP_ARG = "--component-default-group";
    private static final String DEFAULT_GROUP_ARG_SHORT = "-g";

    private static final String THING_NAME_ARG = "--thing-name";
    private static final String THING_NAME_ARG_SHORT = "-tn";
    private static final String THING_NAME_DEFAULT = "GreengrassV2IotThing_" + UUID.randomUUID();

    private static final String THING_GROUP_NAME_ARG = "--thing-group-name";
    private static final String THING_GROUP_NAME_ARG_SHORT = "-tgn";

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
    private static final String AWS_REGION_DEFAULT = "us-east-1";

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

    private static final String GGC_USER = "ggc_user";
    private static final String GGC_GROUP = "ggc_group";
    private static final Logger logger = LogManager.getLogger(GreengrassSetup.class);
    private final String[] setupArgs;
    private final List<String> kernelArgs = new ArrayList<>();
    @Setter
    DeviceProvisioningHelper deviceProvisioningHelper;
    private final PrintStream outStream;
    private final PrintStream errStream;
    private int argpos = 0;
    private String arg;
    private boolean showHelp = false;
    private boolean showVersion = false;
    private String thingName = THING_NAME_DEFAULT;
    private String thingGroupName;
    private String tesRoleName = TES_ROLE_NAME_DEFAULT;
    private String tesRoleAliasName = TES_ROLE_ALIAS_NAME_DEFAULT;
    private String awsRegion = AWS_REGION_DEFAULT;
    private String environmentStage = ENV_STAGE_DEFAULT;
    private String defaultUser;
    private String defaultGroup;
    private boolean needProvisioning = NEED_PROVISIONING_DEFAULT;
    private boolean setupSystemService = SETUP_SYSTEM_SERVICE_ARG_DEFAULT;
    private boolean kernelStart = KERNEL_START_ARG_DEFAULT;
    private boolean deployDevTools = DEPLOY_DEV_TOOLS_ARG_DEFAULT;

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
     * @param setupArgs                CLI args for setup script
     */
    GreengrassSetup(PrintStream outStream, PrintStream errStream, DeviceProvisioningHelper deviceProvisioningHelper,
                    String... setupArgs) {
        this.setupArgs = setupArgs;
        this.outStream = outStream;
        this.errStream = errStream;
        this.deviceProvisioningHelper = deviceProvisioningHelper;
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
            logger.atError().setCause(t).log("Error while trying to setup Greengrass kernel");
            System.err.println("Error while trying to setup Greengrass kernel");
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
            outStream.println(String.format(SHOW_VERSION_RESPONSE, KERNEL_VERSION));
            return;
        }

        setComponentDefaultUserAndGroup();

        Kernel kernel = getKernel();

        //initialize the device provisioning helper
        this.deviceProvisioningHelper = new DeviceProvisioningHelper(awsRegion, environmentStage, this.outStream);

        if (needProvisioning) {
            provision(kernel);
        }

        if (setupSystemService) {
            kernel.shutdown();
            boolean ok = kernel.getContext().get(SystemServiceUtilsFactory.class).getInstance().setupSystemService(
                    kernel.getContext().get(KernelAlternatives.class));
            if (ok) {
                outStream.println("Successfully set up Kernel as a system service");
                // Kernel will be launched by OS as a service
            } else {
                outStream.println("Unable to set up Kernel as a system service");
            }
            return;
        }
        if (!kernelStart) {
            outStream.println("Kernel start set to false, exiting...");
            kernel.shutdown();
            return;
        }
        outStream.println("Launching kernel...");
        kernel.launch();
        outStream.println("Launched kernel successfully.");
    }

    void parseArgs() {
        loop: while (getArg() != null) {
            switch (arg.toLowerCase()) {
                case HELP_ARG:
                case HELP_ARG_SHORT:
                    this.showHelp = true;
                    break loop;
                case VERSION_ARG:
                    this.showVersion = true;
                    break loop;
                case KERNEL_CONFIG_ARG:
                case KERNEL_CONFIG_ARG_SHORT:
                case KERNEL_ROOT_ARG:
                case KERNEL_ROOT_ARG_SHORT:
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
                    kernelArgs.add(arg);
                    this.defaultUser = Coerce.toString(getArg());
                    kernelArgs.add(defaultUser);
                    break;
                case DEFAULT_GROUP_ARG:
                case DEFAULT_GROUP_ARG_SHORT:
                    kernelArgs.add(arg);
                    this.defaultGroup = Coerce.toString(getArg());
                    kernelArgs.add(defaultGroup);
                    break;
                case DEPLOY_DEV_TOOLS_ARG:
                case DEPLOY_DEV_TOOLS_ARG_SHORT:
                    this.deployDevTools = Coerce.toBoolean(getArg());
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }

        if (Region.of(awsRegion) == null) {
            throw new RuntimeException(String.format("%s is invalid AWS region", awsRegion));
        }

        try {
            IotSdkClientFactory.EnvironmentStage.fromString(environmentStage);
        } catch (InvalidEnvironmentStageException e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private String getArg() {
        return arg = setupArgs == null || argpos >= setupArgs.length ? null : setupArgs[argpos++];
    }

    void provision(Kernel kernel) throws IOException, DeviceConfigurationException {
        outStream.printf("Provisioning AWS IoT resources for the device with IoT Thing Name: [%s]...%n", thingName);
        final ThingInfo thingInfo =
                deviceProvisioningHelper.createThing(deviceProvisioningHelper.getIotClient(), thingName);
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
        deviceProvisioningHelper.createAndAttachRolePolicy(tesRoleName);
        outStream.println("Configuring Nucleus with provisioned resource details...");
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, awsRegion, tesRoleAliasName);
        outStream.println("Successfully configured kernel with provisioned resource details!");
        if (deployDevTools) {
            deviceProvisioningHelper.createInitialDeploymentIfNeeded(thingInfo, thingGroupName);
        }

    }

    @SuppressWarnings("PMD.PreserveStackTrace")
    private void setComponentDefaultUserAndGroup() {
        try {
            Platform platform = Platform.getInstance();
            // If not super user and default user option is not provided, the current user will be used
            // as the default user so we do not need to create anything here
            if (!platform.lookupCurrentUser().isSuperUser()) {
                return;
            }
            if (Utils.isEmpty(defaultUser) || GGC_USER.equals(defaultUser)) {
                try {
                    platform.lookupUserByName(GGC_USER);
                    outStream.printf("Got no input for component default user, using %s %n", GGC_USER);
                } catch (IOException e) {
                    outStream.printf("Got no input for component default user, creating %s %n", GGC_USER);
                    platform.createUser(GGC_USER);
                    outStream.printf("%s created %n", GGC_USER);
                }
                kernelArgs.add(DEFAULT_USER_ARG);
                kernelArgs.add(GGC_USER);
                if (Utils.isEmpty(defaultGroup) || GGC_GROUP.equals(defaultGroup)) {
                    try {
                        platform.lookupGroupByName(GGC_GROUP);
                        outStream.printf("Got no input for component default user, using %s %n", GGC_GROUP);
                    } catch (IOException e) {
                        outStream.printf("Got no input for component default group, creating %s %n", GGC_GROUP);
                        platform.createGroup(GGC_GROUP);
                        outStream.printf("%s created %n", GGC_GROUP);
                    }
                    platform.addUserToGroup(GGC_USER, GGC_GROUP);
                    outStream.printf("Added %s to %s %n", GGC_USER, GGC_GROUP);
                    kernelArgs.add(DEFAULT_GROUP_ARG);
                    kernelArgs.add(GGC_GROUP);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error setting up component default user / group", e);
        }
    }

    Kernel getKernel() {
        return new Kernel().parseArgs(kernelArgs.toArray(new String[]{}));
    }

}
