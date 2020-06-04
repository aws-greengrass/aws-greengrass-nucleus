package com.aws.iot.evergreen.easysetup;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagement;
import com.amazonaws.services.greengrasscomponentmanagement.AWSGreengrassComponentManagementClientBuilder;
import com.amazonaws.services.greengrasscomponentmanagement.model.CommitComponentRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.CreateComponentRequest;
import com.amazonaws.services.greengrasscomponentmanagement.model.ResourceAlreadyExistException;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.util.CommitableFile;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AttachPolicyRequest;
import software.amazon.awssdk.services.iot.model.AttachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.CertificateStatus;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iot.model.CreateRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingRequest;
import software.amazon.awssdk.services.iot.model.DescribeEndpointRequest;
import software.amazon.awssdk.services.iot.model.DescribeRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.tes.TokenExchangeService.IOT_ROLE_ALIAS_TOPIC;
import static com.aws.iot.evergreen.tes.TokenExchangeService.TOKEN_EXCHANGE_SERVICE_TOPICS;

/**
 * Provision a device by registering as an IoT thing, creating roles and template first party components.
 */
@Getter
public class DeviceProvisioningHelper {
    private static final Logger logger = LogManager.getLogger(EvergreenSetup.class);

    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    private static final String IOT_ROLE_POLICY_NAME_PREFIX = "EvergreenTESCertificatePolicy";
    private static final String E2E_TESTS_POLICY_NAME_PREFIX = "E2ETestsIotPolicy";
    private static final String E2E_TESTS_THING_NAME_PREFIX = "E2ETestsIotThing";
    // TODO : Remove once global components are implemented
    public static final String GREENGRASS_SERVICE_ENDPOINT =
            "https://3w5ajog718.execute-api.us-east-1.amazonaws.com/Beta/";
    private static final Map<String, String> FIRST_PARTY_COMPONENT_RECIPES = Collections
            .singletonMap(TOKEN_EXCHANGE_SERVICE_TOPICS, "{\n" + "\t\"RecipeTemplateVersion\": \"2020-01-25\",\n"
                    + "\t\"PackageName\": \"TokenExchangeService\",\n"
                    + "\t\"Description\": \"Enable Evergreen devices to interact with AWS services using certs\",\n"
                    + "\t\"Publisher\": \"Evergreen\",\n\t\"Version\": \"1.0.0\"\n}");

    private IotClient iotClient;
    private IamClient iamClient;
    private AWSGreengrassComponentManagement cmsClient;

    /**
     * Constructor for a desired region.
     *
     * @param awsRegion aws region
     */
    public DeviceProvisioningHelper(String awsRegion) {
        this.iotClient = IotSdkClientFactory.getIotClient(awsRegion);
        this.iamClient = IamSdkClientFactory.getIamClient();
        this.cmsClient = AWSGreengrassComponentManagementClientBuilder.standard().withEndpointConfiguration(
                new AwsClientBuilder.EndpointConfiguration(GREENGRASS_SERVICE_ENDPOINT, awsRegion)).build();
    }

    /**
     * Constructor for unit tests.
     *
     * @param iotClient prebuilt IotClient instance
     * @param iamClient prebuilt IamClient instance
     * @param cmsClient prebuilt CmsClient instance
     */
    DeviceProvisioningHelper(IotClient iotClient, IamClient iamClient,
                             AWSGreengrassComponentManagement cmsClient) {
        this.iotClient = iotClient;
        this.iamClient = iamClient;
        this.cmsClient = cmsClient;
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
            logger.atInfo().kv("policy-name", policyName).log("Found IoT policy, reusing it...");
        } catch (ResourceNotFoundException e) {
            logger.atInfo().kv("policy-name", policyName).log("Creating new IoT policy...");
            client.createPolicy(CreatePolicyRequest.builder().policyName(policyName).policyDocument(
                    "{\n  \"Version\": \"2012-10-17\",\n  \"Statement\": [\n    {\n"
                            + "      \"Effect\": \"Allow\",\n      \"Action\": [\n"
                            + "                \"iot:Connect\",\n                \"iot:Publish\",\n"
                            + "                \"iot:Subscribe\",\n                \"iot:Receive\"\n],\n"
                            + "      \"Resource\": \"*\"\n    }\n  ]\n}").build());
        }

        // Create cert
        logger.atInfo().log("Creating keys and certificate...");
        CreateKeysAndCertificateResponse keyResponse =
                client.createKeysAndCertificate(CreateKeysAndCertificateRequest.builder().setAsActive(true).build());

        // Attach policy to cert
        logger.atInfo().log("Attaching policy to certificate...");
        client.attachPolicy(
                AttachPolicyRequest.builder().policyName(policyName).target(keyResponse.certificateArn()).build());

        // Create the thing and attach the cert to it
        logger.atInfo().kv("thing-name", thingName).log("Creating IoT thing...");
        String thingArn = client.createThing(CreateThingRequest.builder().thingName(thingName).build()).thingArn();
        logger.atInfo().log("Attaching certificate to IoT thing...");
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
        client.deleteCertificate(
                DeleteCertificateRequest.builder().certificateId(thing.certificateId).forceDelete(true).build());
    }

    /*
     * Download root CA to a local file.
     */
    private void downloadRootCAToFile(File f) throws IOException {
        logger.atInfo().kv("root-ca-location", ROOT_CA_URL).log("Downloading Root CA...");
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
     * @param kernel Kernel instance
     * @param thing  thing info
     * @param awsRegion aws region
     * @throws IOException Exception while reading root CA from file
     */
    public void updateKernelConfigWithIotConfiguration(Kernel kernel, ThingInfo thing, String awsRegion)
            throws IOException {
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

        DeviceConfiguration config =
                new DeviceConfiguration(kernel, thing.thingName, thing.dataEndpoint, thing.credEndpoint,
                        privKeyFilePath.toString(), certFilePath.toString(), caFilePath.toString(), awsRegion);
        logger.atInfo().kv("config", config).log("Created device configuration");
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
            logger.atInfo().kv("role-alias", roleAliasName)
                    .log("TES role alias with the provided name does not exist, creating new alias...");

            // Get IAM role arn in order to attach an alias to it
            String roleArn;
            try {
                GetRoleRequest getRoleRequest = GetRoleRequest.builder().roleName(roleName).build();
                roleArn = iamClient.getRole(getRoleRequest).role().arn();
            } catch (NoSuchEntityException | ResourceNotFoundException rnfe) {
                logger.atInfo().kv("role-name", roleName)
                        .log("TES role with the provided name does not exist, creating role...");
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
            logger.atInfo().kv("role-policy", iotRolePolicyName)
                    .log("IoT role policy for TES Role alias not exist, creating policy...");
            CreatePolicyRequest createPolicyRequest = CreatePolicyRequest.builder().policyName(iotRolePolicyName)
                    .policyDocument("{\n\t\"Version\": \"2012-10-17\",\n\t\"Statement\": {\n"
                            + "\t\t\"Effect\": \"Allow\",\n\t\t\"Action\": \"iot:AssumeRoleWithCertificate\",\n"
                            + "\t\t\"Resource\": \"" + roleAliasArn + "\"\n\t}\n}").build();
            iotClient.createPolicy(createPolicyRequest);
        }

        logger.atInfo().log("Attaching TES role policy to IoT thing...");
        AttachPolicyRequest attachPolicyRequest =
                AttachPolicyRequest.builder().policyName(iotRolePolicyName).target(certificateArn)
                        .build();
        iotClient.attachPolicy(attachPolicyRequest);
    }

    /**
     * Update the kernel config with TES role alias.
     *
     * @param kernel        Kernel instance
     * @param roleAliasName name of the role alias
     */
    public void updateKernelConfigWithTesRoleInfo(Kernel kernel, String roleAliasName) {
        Topics tesTopics = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, TOKEN_EXCHANGE_SERVICE_TOPICS);
        tesTopics.createLeafChild(IOT_ROLE_ALIAS_TOPIC).withValue(roleAliasName);
    }

    // TODO : Remove once global packages are supported

    /**
     * Create empty packages in customer's account for first party services.
     */
    public void setUpEmptyPackagesForFirstPartyServices() {
        createEmptyComponent(cmsClient, TOKEN_EXCHANGE_SERVICE_TOPICS);
    }

    // TODO : Remove once global packages are supported

    /*
     * Create and commit an empty component.
     */
    private void createEmptyComponent(AWSGreengrassComponentManagement cmsClient, String componentName) {
        logger.atInfo().kv("component-name", componentName).log("Creating an empty component...");
        ByteBuffer recipe =
                ByteBuffer.wrap(FIRST_PARTY_COMPONENT_RECIPES.get(componentName).getBytes(StandardCharsets.UTF_8));
        CreateComponentRequest createComponentRequest = new CreateComponentRequest().withRecipe(recipe);
        try {
            cmsClient.createComponent(createComponentRequest);

            CommitComponentRequest commitComponentRequest =
                    new CommitComponentRequest().withComponentName(componentName).withComponentVersion("1.0.0");
            cmsClient.commitComponent(commitComponentRequest);
        } catch (ResourceAlreadyExistException e) {
            // No need to replace the component if it exists
            logger.atInfo().kv("component-name", componentName)
                    .log("Component already exists, skipping re-creating component");
        }
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
