package com.aws.iot.evergreen.easysetup;

import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.ThingInfo;

/**
 * Easy setup for getting started with Evergreen kernel on a device.
 */
public class EvergreenSetup {
    private static final String SHOW_HELP_RESPONSE = "\n" + "DESCRIPTION\n"
            + "\tInstall Evergreen kernel on your device, register the device as IoT thing, create certificates and "
            + "attach role for TES to them, install the Evergreen device CLI. Please set the AWS credentials"
            + " in the environment variables\n\n"
            + "\n" + "OPTIONS\n" + "\t--config, -i\t\t\tPath to the configuration file to start Evergreen kernel with\n"
            + "\t--root, -r\t\t\t\tPath to the directory to use as the root for Evergreen\n"
            + "\t--thing-name, -tn\t\tDesired thing name to register the device with in AWS IoT cloud\n"
            + "\t--policy-name, -pn \t\tDesired name for IoT thing policy\n"
            + "\t—tes-role-name, -trn \tName of the IAM role to use for TokenExchangeService for the device to talk"
            + " to AWS services, if the role\n"
            + "\t\t\t\t\t\tdoes not exist then it will be created in your AWS account \n"
            + "\t--tes-role-alias-name, -r\t\t\t\tName of the RoleAlias to attach to the IAM role for TES in the AWS "
            + "IoT cloud,"
            + " if the role alias does not exist \t\t\t\t\t\tthen it will be created in your AWS account\n"
            + "\t--provision, -p \t\t\tY/N Indicate if you want to register the device as an AWS IoT thing\n"
            + "\t--aws-region, -ar\t\tAWS region where the resources should be looked for/created\n"
            + "\t--setup-tes, -t \t\t\tY/N Indicate if you want to use Token Exchange Service to talk to"
            + "AWS services using the device certificate\n"
            + "\t--install-cli, -ic \t\t\tY/N Indicate if you want to install Evergreen device CLI";
    private static final String HELP_ARG = "--help";
    private static final String HELP_ARG_SHORT = "-h";

    private static final String KERNEL_CONFIG_ARG = "--config";
    private static final String KERNEL_CONFIG_ARG_SHORT = "-i";
    private static final String KERNEL_ROOT_ARG = "--root";
    private static final String KERNEL_ROOT_ARG_SHORT = "-r";

    private static final String THING_NAME_ARG = "--thing-name";
    private static final String THING_NAME_ARG_SHORT = "-tn";
    private static final String THING_NAME_DEFAULT = "MyIotThing";

    private static final String POLICY_NAME_ARG = "--policy-name";
    private static final String POLICY_NAME_ARG_SHORT = "-pn";
    private static final String POLICY_NAME_DEFAULT = "MyIotThingPolicy";

    // TODO : Customers don't understand TES, when we decide the name for TES to expose
    //  to customers in the context of Evergreen, rename TES related things here and change description
    private static final String TES_ROLE_NAME_ARG = "--tes-role-name";
    private static final String TES_ROLE_NAME_ARG_SHORT = "-trn";
    private static final String TES_ROLE_NAME_DEFAULT = "MyIotRoleForTes";

    private static final String TES_ROLE_ALIAS_NAME_ARG = "--tes-role-alias-name";
    private static final String TES_ROLE_ALIAS_NAME_ARG_SHORT = "-tra";
    private static final String TES_ROLE_ALIAS_NAME_DEFAULT = "MyIotRoleAliasForTes";

    private static final String PROVISION_THING_ARG = "--provision";
    private static final String PROVISION_THING_ARG_SHORT = "-p";
    private static final boolean NEED_PROVISIONING_DEFAULT = false;

    private static final String SETUP_TES_ARG = "--setup-tes";
    private static final String SETUP_TES_ARG_SHORT = "-t";
    private static final boolean SETUP_TES_DEFAULT = false;

    private static final String AWS_REGION_ARG = "--aws-region";
    private static final String AWS_REGION_ARG_SHORT = "-ar";
    private static final String AWS_REGION_DEFAULT = "us-east-1";

    private static final String INSTALL_CLI_ARG = "--install-cli";
    private static final String INSTALL_CLI_ARG_SHORT = "-ic";
    private static final boolean INSTALL_CLI_ARG_DEFAULT = false;

    // TODO : Add optional input for credentials, currently creds are assumed to be set into env vars

    private static final Logger logger = LogManager.getLogger(EvergreenSetup.class);
    private final String[] setupArgs;
    private final List<String> kernelArgs = new ArrayList<>();
    private final DeviceProvisioningHelper deviceProvisioningHelper;
    private int argpos = 0;
    private String arg;
    private boolean showHelp = false;
    private String thingName = THING_NAME_DEFAULT;
    private String policyName = POLICY_NAME_DEFAULT;
    private String tesRoleName = TES_ROLE_NAME_DEFAULT;
    private String tesRoleAliasName = TES_ROLE_ALIAS_NAME_DEFAULT;
    private String awsRegion = AWS_REGION_DEFAULT;
    private boolean needProvisioning = NEED_PROVISIONING_DEFAULT;
    private boolean setupTes = SETUP_TES_DEFAULT;
    private boolean installCli = INSTALL_CLI_ARG_DEFAULT;

    /**
     * Constructor to create an instance using CLI args.
     *
     * @param setupArgs CLI args for setup script
     */
    public EvergreenSetup(String... setupArgs) {
        this.setupArgs = setupArgs;
        parseArgs();
        this.deviceProvisioningHelper = new DeviceProvisioningHelper(awsRegion);
    }

    /**
     * Constructor for unit tests.
     *
     * @param deviceProvisioningHelper Prebuilt DeviceProvisioningHelper instance
     * @param setupArgs                CLI args for setup script
     */
    EvergreenSetup(DeviceProvisioningHelper deviceProvisioningHelper, String... setupArgs) {
        this.setupArgs = setupArgs;
        parseArgs();
        this.deviceProvisioningHelper = deviceProvisioningHelper;
    }

    /**
     * Entry point for setup script.
     *
     * @param args CLI args for setup script
     * @throws Exception error in setup
     */
    @SuppressWarnings({"PMD.NullAssignment", "PMD.AvoidCatchingThrowable", "PMD.DoNotCallSystemExit"})
    public static void main(String[] args) {
        try {
            System.out.println("Setting up the Lifecycle Manager...");
            EvergreenSetup setup = new EvergreenSetup(args);

            // Describe usage of the command
            if (setup.showHelp) {
                logger.atInfo().log(SHOW_HELP_RESPONSE);
                System.exit(0);
            }

            Kernel kernel = new Kernel().parseArgs(setup.kernelArgs.toArray(new String[]{}));

            if (setup.needProvisioning) {
                setup.provision(kernel);
            }

            System.out.println("Starting the kernel...");
            kernel.launch();
            System.out.println("The Evergreen Lifecycle Manager has been setup and started successfully! Please go to localhost:1441 to view the local dashboard.");

            // Install Evergreen cli
            if (setup.installCli) {
                // TODO : Download CLI binary from CDN and install
                logger.atInfo().log("Installed Evergreen CLI");
            }
        } catch (Throwable t) {
            logger.atError().setCause(t).log("Error while trying to setup Evergreen kernel");
            System.out.println("Error while trying to setup Evergreen Lifecycle Manager.");
            System.exit(1);
        }
    }

    private void parseArgs() {
        while (getArg() != null) {
            switch (arg.toLowerCase()) {
                case HELP_ARG:
                case HELP_ARG_SHORT:
                    this.showHelp = true;
                    break;
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
                case POLICY_NAME_ARG:
                case POLICY_NAME_ARG_SHORT:
                    this.policyName = getArg();
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
                    this.awsRegion = getArg();
                    break;
                case PROVISION_THING_ARG:
                case PROVISION_THING_ARG_SHORT:
                    this.needProvisioning = Coerce.toBoolean(getArg());
                    break;
                case SETUP_TES_ARG:
                case SETUP_TES_ARG_SHORT:
                    this.setupTes = Coerce.toBoolean(getArg());
                    break;
                case INSTALL_CLI_ARG:
                case INSTALL_CLI_ARG_SHORT:
                    this.installCli = Coerce.toBoolean(getArg());
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private String getArg() {
        return arg = setupArgs == null || argpos >= setupArgs.length ? null : setupArgs[argpos++];
    }

    void provision(Kernel kernel) throws IOException {
        System.out.println("Provisioning AWS IoT resources for the device...");
        System.out.println("IoT Thing Name: " + thingName);
        ThingInfo thingInfo =
                deviceProvisioningHelper.createThing(deviceProvisioningHelper.getIotClient(), policyName, thingName);
        System.out.println("Succeeded!");
        System.out.println("Configuring kernel with provisioning details...");
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, awsRegion);
        System.out.println("Succeeded!");

        if (setupTes) {
            System.out.println("Setting up resources for TokenExchangeService...");
            deviceProvisioningHelper.setupIoTRoleForTes(tesRoleName, tesRoleAliasName, thingInfo.getCertificateArn());
            System.out.println("Succeeded!");

            System.out.println("Configuring kernel with TokenExchangeService role details...");
            deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, tesRoleAliasName);
            System.out.println("Succeeded!");
            logger.atInfo().log("Creating an empty component for TokenExchangeService...");
            deviceProvisioningHelper.setUpEmptyPackagesForFirstPartyServices();
        }
    }

}
