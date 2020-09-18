package com.aws.iot.evergreen.easysetup;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.util.CommitableFile;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyResponse;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AddThingToThingGroupRequest;
import software.amazon.awssdk.services.iot.model.AttachPolicyRequest;
import software.amazon.awssdk.services.iot.model.AttachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.CertificateStatus;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreateRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.iot.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointRequest;
import software.amazon.awssdk.services.iot.model.DescribeRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.ListAttachedPoliciesRequest;
import software.amazon.awssdk.services.iot.model.Policy;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.iot.evergreen.tes.TokenExchangeService.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;

/**
 * Provision a device by registering as an IoT thing, creating roles and template first party components.
 */
@Getter
public class DeviceProvisioningHelper {
    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    private static final String IOT_ROLE_POLICY_NAME_PREFIX = "EvergreenTESCertificatePolicy";
    private static final String E2E_TESTS_POLICY_NAME_PREFIX = "E2ETestsIotPolicy";
    private static final String E2E_TESTS_THING_NAME_PREFIX = "E2ETestsIotThing";
    // TODO : Remove once global components are implemented
    public static final String GCS_ENDPOINT = "https://nztb5z87k6.execute-api.us-east-1.amazonaws.com/Gamma";

    private final PrintStream outStream;

    private IotClient iotClient;
    private IamClient iamClient;

    /**
     * Constructor for a desired region.
     *
     * @param awsRegion aws region
     * @param outStream stream used to provide customer feedback
     */
    public DeviceProvisioningHelper(String awsRegion, PrintStream outStream) {
        this.iotClient = IotSdkClientFactory.getIotClient(awsRegion);
        this.iamClient = IamSdkClientFactory.getIamClient();
        this.outStream = outStream;
    }

    /**
     * Constructor for unit tests.
     *
     * @param outStream stream to provide customer feedback
     * @param iotClient iot client
     * @param iamClient iam client
     */
    DeviceProvisioningHelper(PrintStream outStream, IotClient iotClient, IamClient iamClient) {
        this.outStream = outStream;
        this.iotClient = iotClient;
        this.iamClient = iamClient;
    }

    /**
     * Create a thing with test configuration.
     *
     * @return created thing info
     */
    public ThingInfo createThingForE2ETests() {
        return createThing(iotClient, E2E_TESTS_POLICY_NAME_PREFIX + UUID.randomUUID().toString(),
                E2E_TESTS_THING_NAME_PREFIX + UUID.randomUUID().toString());
    }

    /**
     * Create a thing with provided configuration.
     *
     * @param client     iotClient to use
     * @param policyName policyName
     * @param thingName  thingName
     * @return created thing info
     */
    public ThingInfo createThing(IotClient client, String policyName, String thingName) {
        // Find or create IoT policy
        try {
            client.getPolicy(GetPolicyRequest.builder().policyName(policyName).build());
            outStream.println(String.format("Found IoT policy \"%s\", reusing it", policyName));
        } catch (ResourceNotFoundException e) {
            outStream.println(String.format("Creating new IoT policy \"%s\"", policyName));
            client.createPolicy(CreatePolicyRequest.builder().policyName(policyName).policyDocument(
                    "{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [\n    {\n"
                            + "      \"Effect\": \"Allow\",\n      \"Action\": [\n"
                            + "                \"iot:Connect\",\n                \"iot:Publish\",\n"
                            + "                \"iot:Subscribe\",\n                \"iot:Receive\"\n],\n"
                            + "      \"Resource\": \"*\"\n    }\n  ]\n}").build());
        }

        // Create cert
        outStream.println("Creating keys and certificate...");
        CreateKeysAndCertificateResponse keyResponse =
                client.createKeysAndCertificate(CreateKeysAndCertificateRequest.builder().setAsActive(true).build());

        // Attach policy to cert
        outStream.println("Attaching policy to certificate...");
        client.attachPolicy(
                AttachPolicyRequest.builder().policyName(policyName).target(keyResponse.certificateArn()).build());

        // Create the thing and attach the cert to it
        outStream.println(String.format("Creating IoT Thing \"%s\"...", thingName));
        String thingArn = client.createThing(CreateThingRequest.builder().thingName(thingName).build()).thingArn();
        outStream.println("Attaching certificate to IoT thing...");
        client.attachThingPrincipal(
                AttachThingPrincipalRequest.builder().thingName(thingName).principal(keyResponse.certificateArn())
                        .build());

        return new ThingInfo(thingArn, thingName, keyResponse.certificateArn(), keyResponse.certificateId(),
                keyResponse.certificatePem(), keyResponse.keyPair(),
                client.describeEndpoint(DescribeEndpointRequest.builder().endpointType("iot:Data-ATS").build())
                        .endpointAddress(), client.describeEndpoint(
                DescribeEndpointRequest.builder().endpointType("iot:CredentialProvider").build()).endpointAddress());
    }

    /**
     * Clean up an existing thing from AWS account using the provided client.
     *
     * @param client iotClient to use
     * @param thing  thing info
     */
    public void cleanThing(IotClient client, ThingInfo thing) {
        client.detachThingPrincipal(
                DetachThingPrincipalRequest.builder().thingName(thing.thingName).principal(thing.certificateArn)
                        .build());
        client.deleteThing(DeleteThingRequest.builder().thingName(thing.thingName).build());
        client.updateCertificate(UpdateCertificateRequest.builder().certificateId(thing.certificateId)
                .newStatus(CertificateStatus.INACTIVE).build());
        for (Policy p : client
                .listAttachedPolicies(ListAttachedPoliciesRequest.builder().target(thing.certificateArn).build())
                .policies()) {
            client.detachPolicy(
                    DetachPolicyRequest.builder().policyName(p.policyName()).target(thing.certificateArn).build());
            client.deletePolicy(DeletePolicyRequest.builder().policyName(p.policyName()).build());
        }
        client.deleteCertificate(
                DeleteCertificateRequest.builder().certificateId(thing.certificateId).forceDelete(true).build());
    }

    /*
     * Download root CA to a local file.
     */
    private void downloadRootCAToFile(File f) throws IOException {
        outStream.println(String.format("Downloading Root CA from \"%s\"", ROOT_CA_URL));
        downloadFileFromURL(ROOT_CA_URL, f);
    }

    /*
     * Download content from a URL to a local file.
     */
    @SuppressWarnings("PMD.AvoidFileStream")
    private void downloadFileFromURL(String url, File f) throws IOException {
        try (ReadableByteChannel readableByteChannel = Channels.newChannel(new URL(url).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(f)) {
            fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
        }
    }

    /**
     * Update the kernel config with iot thing info, in specific CA, private Key and cert path.
     *
     * @param kernel    Kernel instance
     * @param thing     thing info
     * @param awsRegion aws region
     * @throws IOException Exception while reading root CA from file
     * @throws DeviceConfigurationException when the configuration parameters are not valid
     */
    public void updateKernelConfigWithIotConfiguration(Kernel kernel, ThingInfo thing, String awsRegion)
            throws IOException, DeviceConfigurationException {
        Path rootDir = kernel.getRootPath();
        Path caFilePath = rootDir.resolve("rootCA.pem");
        Path privKeyFilePath = rootDir.resolve("privKey.key");
        Path certFilePath = rootDir.resolve("thingCert.crt");

        downloadRootCAToFile(caFilePath.toFile());
        try (CommitableFile cf = CommitableFile.of(privKeyFilePath, true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(certFilePath, true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }

        new DeviceConfiguration(kernel, thing.thingName, thing.dataEndpoint, thing.credEndpoint,
                privKeyFilePath.toString(), certFilePath.toString(), caFilePath.toString(), awsRegion);
        outStream.println("Created device configuration");
    }

    /**
     * Create IoT role for using TES.
     *
     * @param roleName       rolaName
     * @param roleAliasName  roleAlias name
     * @param certificateArn certificate arn for the IoT thing
     */
    public void setupIoTRoleForTes(String roleName, String roleAliasName, String certificateArn) {
        String roleAliasArn;
        try {
            // Get Role Alias arn
            DescribeRoleAliasRequest describeRoleAliasRequest =
                    DescribeRoleAliasRequest.builder().roleAlias(roleAliasName).build();
            roleAliasArn = iotClient.describeRoleAlias(describeRoleAliasRequest).roleAliasDescription().roleAliasArn();
        } catch (ResourceNotFoundException ranfe) {
            outStream.println(
                    String.format("TES role alias \"%s\" does not exist, creating new alias...", roleAliasName));

            // Get IAM role arn in order to attach an alias to it
            String roleArn;
            try {
                GetRoleRequest getRoleRequest = GetRoleRequest.builder().roleName(roleName).build();
                roleArn = iamClient.getRole(getRoleRequest).role().arn();
            } catch (NoSuchEntityException | ResourceNotFoundException rnfe) {
                outStream.println(String.format("TES role \"%s\" does not exist, creating role...", roleName));
                CreateRoleRequest createRoleRequest = CreateRoleRequest.builder().roleName(roleName).description(
                        "Role for Evergreen IoT things to interact with AWS services using token exchange service")
                        .assumeRolePolicyDocument("{\n  \"Version\": \"2012-10-17\",\n"
                                + "  \"Statement\": [\n    {\n      \"Effect\": \"Allow\",\n"
                                + "      \"Principal\": {\n        \"Service\": \"credentials.iot.amazonaws.com\"\n"
                                + "      },\n      \"Action\": \"sts:AssumeRole\"\n    }\n  ]\n}").build();
                roleArn = iamClient.createRole(createRoleRequest).role().arn();
            }

            CreateRoleAliasRequest createRoleAliasRequest =
                    CreateRoleAliasRequest.builder().roleArn(roleArn).roleAlias(roleAliasName).build();
            roleAliasArn = iotClient.createRoleAlias(createRoleAliasRequest).roleAliasArn();
        }

        // Attach policy role alias to cert
        String iotRolePolicyName = IOT_ROLE_POLICY_NAME_PREFIX + roleAliasName;
        try {
            iotClient.getPolicy(GetPolicyRequest.builder().policyName(iotRolePolicyName).build());
        } catch (ResourceNotFoundException e) {
            outStream.println(String.format("IoT role policy \"%s\" for TES Role alias not exist, creating policy...",
                    iotRolePolicyName));
            CreatePolicyRequest createPolicyRequest = CreatePolicyRequest.builder().policyName(iotRolePolicyName)
                    .policyDocument("{\n\t\"Version\": \"2012-10-17\",\n\t\"Statement\": {\n"
                            + "\t\t\"Effect\": \"Allow\",\n\t\t\"Action\": \"iot:AssumeRoleWithCertificate\",\n"
                            + "\t\t\"Resource\": \"" + roleAliasArn + "\"\n\t}\n}").build();
            iotClient.createPolicy(createPolicyRequest);
        }

        outStream.println("Attaching TES role policy to IoT thing...");
        AttachPolicyRequest attachPolicyRequest =
                AttachPolicyRequest.builder().policyName(iotRolePolicyName).target(certificateArn).build();
        iotClient.attachPolicy(attachPolicyRequest);
    }

    /**
     * Creates IAM policy using specified name and document. Attach the policy to given IAM role name.
     *
     * @param roleName           name of target role
     * @param rolePolicyName     name of policy to create and attach
     * @param rolePolicyDocument document of policy to create and attach
     * @return ARN of created policy
     */
    public Optional<String> createAndAttachRolePolicy(String roleName, String rolePolicyName,
                                                      String rolePolicyDocument) {
        try {
            String tesRolePolicyArn;
            CreatePolicyResponse createPolicyResponse = iamClient.createPolicy(
                    software.amazon.awssdk.services.iam.model.CreatePolicyRequest.builder().policyName(rolePolicyName)
                            .policyDocument(rolePolicyDocument).build());
            tesRolePolicyArn = createPolicyResponse.policy().arn();
            outStream.printf("IAM role policy for TES \"%s\" created%n", rolePolicyName);
            outStream.println("Attaching IAM role policy for TES to IAM role for TES...");
            iamClient.attachRolePolicy(
                    AttachRolePolicyRequest.builder().roleName(roleName).policyArn(tesRolePolicyArn).build());
            return Optional.of(tesRolePolicyArn);
        } catch (EntityAlreadyExistsException e) {
            // TODO get and reuse the policy. non trivial because we can only get IAM policy by ARN
            outStream.printf("IAM policy named \"%s\" already exists. Please attach it to the IAM role if not "
                    + "already%n", rolePolicyName);
            return Optional.empty();
        }
    }

    /**
     * Update the kernel config with TES role alias.
     *
     * @param kernel        Kernel instance
     * @param roleAliasName name of the role alias
     */
    public void updateKernelConfigWithTesRoleInfo(Kernel kernel, String roleAliasName) {
        Topics tesTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, TOKEN_EXCHANGE_SERVICE_TOPICS);
        tesTopics.lookup(PARAMETERS_CONFIG_KEY, IOT_ROLE_ALIAS_TOPIC).withValue(roleAliasName);
    }

    /**
     * Add an existing Thing into a Thing Group which may or may not exist.
     *
     * @param iotClient client
     * @param thingName thing name
     * @param thingGroupName group to add the thing into
     */
    public void addThingToGroup(IotClient iotClient, String thingName, String thingGroupName) {
        try {
            iotClient.createThingGroup(CreateThingGroupRequest.builder().thingGroupName(thingGroupName).build());
        } catch (ResourceAlreadyExistsException e) {
            outStream.printf("IoT Thing Group \"%s\" already existed, reusing it%n", thingGroupName);
        }
        iotClient.addThingToThingGroup(AddThingToThingGroupRequest.builder()
                .thingName(thingName).thingGroupName(thingGroupName).build());
    }

    @AllArgsConstructor
    @Getter
    public static class ThingInfo {
        private String thingArn;
        private String thingName;
        private String certificateArn;
        private String certificateId;
        private String certificatePem;
        private KeyPair keyPair;
        private String dataEndpoint;
        private String credEndpoint;
    }
}
