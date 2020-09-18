package com.aws.iot.evergreen.easysetup;

import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.KernelAlternatives;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.Coerce;
import com.aws.iot.evergreen.util.Utils;
import com.aws.iot.evergreen.util.orchestration.SystemServiceUtilsFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.ThingInfo;

/**
 * Easy setup for getting started with Evergreen kernel on a device.
 */
public class EvergreenSetup {
    private static final String SHOW_HELP_RESPONSE = "\n" + "DESCRIPTION\n"
            + "\tInstall Evergreen kernel on your device, register the device as IoT thing,\n"
            + "\tcreate certificates and attach role for TES to them, install the Evergreen\n"
            + "\tdevice CLI. Please set the AWS credentials in the environment variables\n"
            + "\nOPTIONS\n"
            + "\t--config, -i\t\tPath to the configuration file to start Evergreen kernel with\n"
            + "\t--root, -r\t\tPath to the directory to use as the root for Evergreen\n"
            + "\t--thing-name, -tn\t\tDesired thing name to register the device with in AWS IoT cloud\n"
            + "\t--thing-group-name, -tgn\t\tDesired thing group to add the IoT Thing into\n"
            + "\t--policy-name, -pn\t\tDesired name for IoT thing policy\n"
            + "\t—tes-role-name, -trn\t\tName of the IAM role to use for TokenExchangeService for the device to talk\n"
            + "\t\t\tto AWS services, if the role does not exist then it will be created in your AWS account\n"
            + "\t--tes-role-policy-name, -trpn\t\tName of the IAM policy to create and attach to the TES role\n"
            + "\t--tes-role-policy-doc, -trpd\t\tJSON policy document for the IAM policy to create and attach to the "
            + "TES role\n"
            + "\t--tes-role-alias-name, -tra\t\tName of the RoleAlias to attach to the IAM role for TES in the AWS\n"
            + "\t\t\tIoT cloud, if the role alias does not exist then it will be created in your AWS account\n"
            + "\t--provision, -p\t\tY/N Indicate if you want to register the device as an AWS IoT thing\n"
            + "\t--aws-region, -ar\t\tAWS region where the resources should be looked for/created\n"
            + "\t--setup-tes, -t\t\tY/N Indicate if you want to use Token Exchange Service to talk to"
            + " AWS services using the device certificate\n"
            + "\t--install-cli, -ic\t\tY/N Indicate if you want to install Evergreen device CLI\n"
            + "\t--setup-system-service, -ss\t\tY/N Indicate if you want to setup Evergreen as a system service\n";

    private static final String HELP_ARG = "--help";
    private static final String HELP_ARG_SHORT = "-h";

    private static final String KERNEL_CONFIG_ARG = "--config";
    private static final String KERNEL_CONFIG_ARG_SHORT = "-i";
    private static final String KERNEL_ROOT_ARG = "--root";
    private static final String KERNEL_ROOT_ARG_SHORT = "-r";

    private static final String THING_NAME_ARG = "--thing-name";
    private static final String THING_NAME_ARG_SHORT = "-tn";
    private static final String THING_NAME_DEFAULT = "MyIotThing";

    private static final String THING_GROUP_NAME_ARG = "--thing-group-name";
    private static final String THING_GROUP_NAME_ARG_SHORT = "-tgn";
    private static final String THING_GROUP_NAME_DEFAULT = "MyIotThingGroup";

    private static final String POLICY_NAME_ARG = "--policy-name";
    private static final String POLICY_NAME_ARG_SHORT = "-pn";
    private static final String POLICY_NAME_DEFAULT = "MyIotThingPolicy";

    // TODO : Customers don't understand TES, when we decide the name for TES to expose
    //  to customers in the context of Evergreen, rename TES related things here and change description
    private static final String TES_ROLE_NAME_ARG = "--tes-role-name";
    private static final String TES_ROLE_NAME_ARG_SHORT = "-trn";
    private static final String TES_ROLE_NAME_DEFAULT = "MyIotRoleForTes";

    // no defaults. must user input
    private static final String TES_ROLE_POLICY_NAME_ARG = "--tes-role-policy-name";
    private static final String TES_ROLE_POLICY_NAME_ARG_SHORT = "-trpn";
    private static final String TES_ROLE_POLICY_DOC_ARG = "--tes-role-policy-doc";
    private static final String TES_ROLE_POLICY_DOC_ARG_SHORT = "-trpd";

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

    private static final String SETUP_SYSTEM_SERVICE_ARG = "--setup-system-service";
    private static final String SETUP_SYSTEM_SERVICE_ARG_SHORT = "-ss";
    private static final boolean SETUP_SYSTEM_SERVICE_ARG_DEFAULT = false;

    private static final String KERNEL_START_ARG = "--start";
    private static final String KERNEL_START_ARG_SHORT = "-s";
    private static final boolean KERNEL_START_ARG_DEFAULT = true;

    // TODO : Add optional input for credentials, currently creds are assumed to be set into env vars

    private static final Logger logger = LogManager.getLogger(EvergreenSetup.class);
    private final String[] setupArgs;
    private final List<String> kernelArgs = new ArrayList<>();
    private final DeviceProvisioningHelper deviceProvisioningHelper;
    private final PrintStream outStream;
    private final PrintStream errStream;
    private int argpos = 0;
    private String arg;
    private boolean showHelp = false;
    private String thingName = THING_NAME_DEFAULT;
    private String thingGroupName = THING_GROUP_NAME_DEFAULT;
    private String policyName = POLICY_NAME_DEFAULT;
    private String tesRoleName = TES_ROLE_NAME_DEFAULT;
    private String tesRoleAliasName = TES_ROLE_ALIAS_NAME_DEFAULT;
    private String tesRolePolicyName;
    private String tesRolePolicyDoc;
    private String awsRegion = AWS_REGION_DEFAULT;
    private boolean needProvisioning = NEED_PROVISIONING_DEFAULT;
    private boolean setupTes = SETUP_TES_DEFAULT;
    private boolean installCli = INSTALL_CLI_ARG_DEFAULT;
    private boolean setupSystemService = SETUP_SYSTEM_SERVICE_ARG_DEFAULT;
    private boolean kernelStart = KERNEL_START_ARG_DEFAULT;

    /**
     * Constructor to create an instance using CLI args.
     *
     * @param outStream writer to use to send text response to user
     * @param errStream writer to use to send error response to user
     * @param setupArgs CLI args for setup script
     */
    public EvergreenSetup(PrintStream outStream, PrintStream errStream, String... setupArgs) {
        this.setupArgs = setupArgs;
        this.outStream = outStream;
        this.errStream = errStream;
        this.deviceProvisioningHelper = new DeviceProvisioningHelper(awsRegion, this.outStream);
    }

    /**
     * Constructor for unit tests.
     *
     * @param outStream                writer to use to send text response to user
     * @param errStream                writer to use to send error response to user
     * @param deviceProvisioningHelper Prebuilt DeviceProvisioningHelper instance
     * @param setupArgs                CLI args for setup script
     */
    EvergreenSetup(PrintStream outStream, PrintStream errStream, DeviceProvisioningHelper deviceProvisioningHelper,
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
        EvergreenSetup evergreenSetup = new EvergreenSetup(System.out, System.err, args);
        try {
            evergreenSetup.parseArgs();
            evergreenSetup.performSetup();
        } catch (Throwable t) {
            logger.atError().setCause(t).log("Error while trying to setup Evergreen kernel");
            System.err.println("Error while trying to setup Evergreen kernel");
            t.printStackTrace(evergreenSetup.errStream);
            System.exit(1);
        }
    }

    void performSetup() throws IOException, DeviceConfigurationException {
        // Describe usage of the command
        if (showHelp) {
            outStream.println(SHOW_HELP_RESPONSE);
            return;
        }

        Kernel kernel = getKernel();

        if (needProvisioning) {
            provision(kernel);
        }

        // Install Evergreen cli
        if (installCli) {
            // TODO : Download CLI binary from CDN and install
            outStream.println("Installed Evergreen CLI");
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
                case POLICY_NAME_ARG:
                case POLICY_NAME_ARG_SHORT:
                    this.policyName = getArg();
                    break;
                case TES_ROLE_NAME_ARG:
                case TES_ROLE_NAME_ARG_SHORT:
                    this.tesRoleName = getArg();
                    break;
                case TES_ROLE_POLICY_NAME_ARG:
                case TES_ROLE_POLICY_NAME_ARG_SHORT:
                    this.tesRolePolicyName = getArg();
                    break;
                case TES_ROLE_POLICY_DOC_ARG:
                case TES_ROLE_POLICY_DOC_ARG_SHORT:
                    try {
                        this.tesRolePolicyDoc = Utils.loadParamMaybeFile(getArg());
                    } catch (URISyntaxException | IOException e) {
                        throw new RuntimeException(
                                String.format("Error loading TES role policy doc: %s", this.tesRolePolicyDoc), e);
                    }
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
                case SETUP_SYSTEM_SERVICE_ARG:
                case SETUP_SYSTEM_SERVICE_ARG_SHORT:
                    this.setupSystemService = Coerce.toBoolean(getArg());
                    break;
                case KERNEL_START_ARG:
                case KERNEL_START_ARG_SHORT:
                    this.kernelStart = Coerce.toBoolean(getArg());
                    break;
                default:
                    RuntimeException rte =
                            new RuntimeException(String.format("Undefined command line argument: %s", arg));
                    logger.atError().setEventType("parse-args-error").setCause(rte).log();
                    throw rte;
            }
        }

        // validate args
        if (this.tesRolePolicyName == null ^ this.tesRolePolicyDoc == null) {
            throw new RuntimeException(String.format("%s and %s must be provided together", TES_ROLE_POLICY_NAME_ARG,
                    TES_ROLE_POLICY_DOC_ARG));
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private String getArg() {
        return arg = setupArgs == null || argpos >= setupArgs.length ? null : setupArgs[argpos++];
    }

    void provision(Kernel kernel) throws IOException, DeviceConfigurationException {
        outStream.printf("Provisioning AWS IoT resources for the device with IoT Thing Name: [%s]...%n", thingName);
        final ThingInfo thingInfo =
                deviceProvisioningHelper.createThing(deviceProvisioningHelper.getIotClient(), policyName, thingName);
        outStream.printf("Successfully provisioned AWS IoT resources for the device with IoT Thing Name: [%s]!%n",
                thingName);
        if (!Utils.isEmpty(thingGroupName)) {
            outStream.printf("Adding IoT Thing [%s] into Thing Group: [%s]...%n", thingName, thingGroupName);
                deviceProvisioningHelper.addThingToGroup(deviceProvisioningHelper.getIotClient(), thingName,
                        thingGroupName);
            outStream.printf("Successfully added Thing into Thing Group: [%s]%n", thingGroupName);
        }

        outStream.println("Configuring kernel with provisioned resource details...");
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, awsRegion);
        outStream.println("Successfully configured kernel with provisioned resource details!");


        if (setupTes) {
            outStream.println("Setting up resources for TokenExchangeService...");
            deviceProvisioningHelper.setupIoTRoleForTes(tesRoleName, tesRoleAliasName, thingInfo.getCertificateArn());
            if (tesRolePolicyName != null && tesRolePolicyDoc != null) {
                deviceProvisioningHelper.createAndAttachRolePolicy(tesRoleName, tesRolePolicyName, tesRolePolicyDoc);
            }
            outStream.println("Configuring kernel with TokenExchangeService role details...");
            deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, tesRoleAliasName);
            outStream.println("Successfully configured TokenExchangeService!");
        }
    }

    Kernel getKernel() {
        return new Kernel().parseArgs(kernelArgs.toArray(new String[]{}));
    }

}
