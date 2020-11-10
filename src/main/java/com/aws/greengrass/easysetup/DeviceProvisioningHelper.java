/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.AWSEvergreenClientBuilder;
import com.amazonaws.services.evergreen.model.ComponentInfo;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicy;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.amazonaws.services.evergreen.model.ConfigurationValidationPolicy;
import com.amazonaws.services.evergreen.model.CreateDeploymentRequest;
import com.amazonaws.services.evergreen.model.DeploymentPolicies;
import com.amazonaws.services.evergreen.model.FailureHandlingPolicy;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.CommitableFile;
import com.aws.greengrass.util.IamSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory.EnvironmentStage;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
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
import software.amazon.awssdk.services.iot.model.DescribeThingGroupRequest;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.GetPolicyRequest;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.ListAttachedPoliciesRequest;
import software.amazon.awssdk.services.iot.model.Policy;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Provision a device by registering as an IoT thing, creating roles and template first party components.
 */
@Getter
public class DeviceProvisioningHelper {
    private static final String GG_THING_POLICY_NAME = "GreengrassV2IoTThingPolicy";
    private static final String GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_SUFFIX = "Access";
    private static final String GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_DOCUMENT =
            "{\n" + "    \"Version\": \"2012-10-17\",\n"
                    + "    \"Statement\": [\n"
                    + "        {\n"
                    + "            \"Effect\": \"Allow\",\n"
                    + "            \"Action\": [\n"
                    + "                \"iot:DescribeCertificate\",\n"
                    + "                \"logs:CreateLogGroup\",\n"
                    + "                \"logs:CreateLogStream\",\n"
                    + "                \"logs:PutLogEvents\",\n"
                    + "                \"logs:DescribeLogStreams\",\n"
                    + "                \"iot:Connect\",\n"
                    + "                \"iot:Publish\",\n"
                    + "                \"iot:Subscribe\",\n"
                    + "                \"iot:Receive\",\n"
                    + "                \"s3:GetBucketLocation\"\n"
                    + "            ],\n"
                    + "            \"Resource\": \"*\"\n"
                    + "        }\n"
                    + "    ]\n"
                    + "}";
    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    private static final String IOT_ROLE_POLICY_NAME_PREFIX = "GreengrassTESCertificatePolicy";
    private static final String HTTP_DEBUG_VIEW_COMPONENT_NAME = "aws.greengrass.HttpDebugView";
    private static final String HTTP_DEBUG_VIEW_COMPONENT_VERSION = "1.0.0";
    private static final String GREENGRASS_CLI_COMPONENT_NAME = "aws.greengrass.Cli";
    private static final String GREENGRASS_CLI_COMPONENT_VERSION = "1.0.0";

    private static final String E2E_TESTS_POLICY_NAME_PREFIX = "E2ETestsIotPolicy";
    private static final String E2E_TESTS_THING_NAME_PREFIX = "E2ETestsIotThing";

    public static final Map<EnvironmentStage, String> GREENGRASS_SERVICE_STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            EnvironmentStage.PROD, "greengrass-ats.iot.%s.amazonaws.com:8443/greengrass",
            EnvironmentStage.GAMMA, "greengrass-ats.gamma.%s.iot.amazonaws.com:8443/greengrass",
            EnvironmentStage.BETA, "greengrass-ats.beta.%s.iot.amazonaws.com:8443/greengrass"
    );
    private final Map<EnvironmentStage, String> tesServiceEndpoints = ImmutableMap.of(
            EnvironmentStage.PROD, "credentials.iot.amazonaws.com",
            EnvironmentStage.GAMMA, "credentials.iot.test.amazonaws.com",
            EnvironmentStage.BETA, "credentials.iot.test.amazonaws.com"
    );
    private static final Map<IotSdkClientFactory.EnvironmentStage, String> STAGE_TO_ENDPOINT_FORMAT = ImmutableMap.of(
            IotSdkClientFactory.EnvironmentStage.PROD, "evergreen.%s.amazonaws.com",
            IotSdkClientFactory.EnvironmentStage.GAMMA, "evergreen-gamma.%s.amazonaws.com",
            IotSdkClientFactory.EnvironmentStage.BETA, "evergreen-beta.%s.amazonaws.com"
    );
    private final PrintStream outStream;
    private final IotClient iotClient;
    private final IamClient iamClient;
    private final AWSEvergreen greengrassClient;
    private EnvironmentStage envStage = EnvironmentStage.PROD;

    /**
     * Constructor for a desired region and stage.
     *
     * @param awsRegion        aws region
     * @param outStream        stream used to provide customer feedback
     * @param environmentStage {@link EnvironmentStage}
     * @throws URISyntaxException               when Iot endpoint is malformed
     * @throws InvalidEnvironmentStageException when the environmentStage passes is invalid
     */
    public DeviceProvisioningHelper(String awsRegion, String environmentStage, PrintStream outStream)
            throws URISyntaxException, InvalidEnvironmentStageException {
        this.outStream = outStream;
        this.envStage = StringUtils.isEmpty(environmentStage) ? EnvironmentStage.PROD
                : EnvironmentStage.fromString(environmentStage);
        this.iotClient = IotSdkClientFactory.getIotClient(awsRegion, envStage);
        this.iamClient = IamSdkClientFactory.getIamClient();
        this.greengrassClient = AWSEvergreenClientBuilder.standard().withEndpointConfiguration(
                new AwsClientBuilder.EndpointConfiguration(
                        String.format(STAGE_TO_ENDPOINT_FORMAT.get(envStage), awsRegion), awsRegion)).build();

    }

    /**
     * Constructor for unit tests.
     *
     * @param outStream        stream to provide customer feedback
     * @param iotClient        iot client
     * @param iamClient        iam client
     * @param greengrassClient Greengrass client
     */
    DeviceProvisioningHelper(PrintStream outStream, IotClient iotClient, IamClient iamClient,
                             AWSEvergreen greengrassClient) {
        this.outStream = outStream;
        this.iotClient = iotClient;
        this.iamClient = iamClient;
        this.greengrassClient = greengrassClient;
    }

    /**
     * Create a thing with test configuration.
     *
     * @return created thing info
     */
    public ThingInfo createThingForE2ETests() {
        return createThing(iotClient, E2E_TESTS_POLICY_NAME_PREFIX,
                E2E_TESTS_THING_NAME_PREFIX + UUID.randomUUID().toString());
    }

    /**
     * Create a thing with provided configuration.
     *
     * @param client    iotClient to use
     * @param thingName thingName
     * @return created thing info
     */
    public ThingInfo createThing(IotClient client, String thingName) {
        return createThing(client, GG_THING_POLICY_NAME, thingName);
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
                            + "                \"iot:Subscribe\",\n                \"iot:Receive\",\n"
                            + "                \"greengrass:*\"\n],\n"
                            + "      \"Resource\": \"*\"\n    }\n  ]\n}")
                    .build());
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
     * @param client         iotClient to use
     * @param thing          thing info
     * @param deletePolicies true if iot policies should be deleted
     */
    public void cleanThing(IotClient client, ThingInfo thing, boolean deletePolicies) {
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
            if (deletePolicies) {
                client.deletePolicy(DeletePolicyRequest.builder().policyName(p.policyName()).build());
            }
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
     * @param kernel        Kernel instance
     * @param thing         thing info
     * @param awsRegion     aws region
     * @param roleAliasName role alias for using IoT credentials endpoint
     * @throws IOException                  Exception while reading root CA from file
     * @throws DeviceConfigurationException when the configuration parameters are not valid
     */
    public void updateKernelConfigWithIotConfiguration(Kernel kernel, ThingInfo thing, String awsRegion,
                                                       String roleAliasName)
            throws IOException, DeviceConfigurationException {
        Path rootDir = kernel.getNucleusPaths().rootPath();
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
                privKeyFilePath.toString(), certFilePath.toString(), caFilePath.toString(), awsRegion, roleAliasName);
        // Make sure tlog persists the device configuration
        kernel.getContext().waitForPublishQueueToClear();
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
                        "Role for Greengrass IoT things to interact with AWS services using token exchange service")
                        .assumeRolePolicyDocument("{\n  \"Version\": \"2012-10-17\",\n"
                                + "  \"Statement\": [\n    {\n      \"Effect\": \"Allow\",\n"
                                + "      \"Principal\": {\n       \"Service\": \"" + tesServiceEndpoints.get(envStage)
                                + "\"\n      },\n      \"Action\": \"sts:AssumeRole\"\n    }\n  ]\n}").build();
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
     * @param roleName name of target role
     * @return ARN of created policy
     */
    public Optional<String> createAndAttachRolePolicy(String roleName) {
        return createAndAttachRolePolicy(roleName, roleName + GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_SUFFIX,
                GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_DOCUMENT);
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
            outStream.printf("IAM role policy for TES \"%s\" created. This policy DOES NOT have S3 access, please "
                            + "modify it with your private components' artifact buckets/objects as needed when you "
                    + "create and deploy private components %n", rolePolicyName);
            outStream.println("Attaching IAM role policy for TES to IAM role for TES...");
            iamClient.attachRolePolicy(
                    AttachRolePolicyRequest.builder().roleName(roleName).policyArn(tesRolePolicyArn).build());
            return Optional.of(tesRolePolicyArn);
        } catch (EntityAlreadyExistsException e) {
            // TODO: [P41215965] get and reuse the policy. non trivial because we can only get IAM policy by ARN
            outStream.printf("IAM policy named \"%s\" already exists. Please attach it to the IAM role if not "
                    + "already%n", rolePolicyName);
            return Optional.empty();
        }
    }

    /**
     * Add an existing Thing into a Thing Group which may or may not exist.
     *
     * @param iotClient      client
     * @param thingName      thing name
     * @param thingGroupName group to add the thing into
     */
    public void addThingToGroup(IotClient iotClient, String thingName, String thingGroupName) {
        try {
            iotClient.createThingGroup(CreateThingGroupRequest.builder().thingGroupName(thingGroupName).build());
        } catch (ResourceAlreadyExistsException e) {
            outStream.printf("IoT Thing Group \"%s\" already existed, reusing it%n", thingGroupName);
        }
        iotClient.addThingToThingGroup(
                AddThingToThingGroupRequest.builder().thingName(thingName).thingGroupName(thingGroupName).build());
    }

    private boolean thingGroupExists(String thingGroupName) {
        try {
            return thingGroupName.equals(iotClient
                    .describeThingGroup(DescribeThingGroupRequest.builder().thingGroupName(thingGroupName).build())
                    .thingGroupName());
        } catch (ResourceNotFoundException e) {
            return false;
        }
    }

    /**
     * Creates an initial deployment to deploy dev tools like the Greengrass CLI and the Http Debug View component.
     *
     * @param thingInfo thing info for the device
     * @param thingGroupName thing group name
     */
    public void createInitialDeploymentIfNeeded(ThingInfo thingInfo, String thingGroupName) {
        if (Utils.isNotEmpty(thingGroupName) && thingGroupExists(thingGroupName)) {
            outStream.println(
                    "Thing group exists, no need to create a deployment for Greengrass first party components");
            return;
        }

        CreateDeploymentRequest deploymentRequest = new CreateDeploymentRequest().withDeploymentPolicies(
                new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(60))
                        .withComponentUpdatePolicy(
                                new ComponentUpdatePolicy().withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS)
                                        .withTimeout(60)).withFailureHandlingPolicy(FailureHandlingPolicy.ROLLBACK));

        if (Utils.isNotEmpty(thingGroupName)) {
            outStream.println("Creating a deployment for Greengrass first party components to the thing group");
            deploymentRequest.withTargetName(thingGroupName).withTargetType("thinggroup");
        } else {
            outStream.println("Creating a deployment for Greengrass first party components to the device");
            deploymentRequest.withTargetName(thingInfo.thingName).withTargetType("thing");
        }

        deploymentRequest.addComponentsEntry(HTTP_DEBUG_VIEW_COMPONENT_NAME,
                new ComponentInfo().withVersion(HTTP_DEBUG_VIEW_COMPONENT_VERSION))
                .addComponentsEntry(GREENGRASS_CLI_COMPONENT_NAME,
                        new ComponentInfo().withVersion(GREENGRASS_CLI_COMPONENT_VERSION));

        greengrassClient.createDeployment(deploymentRequest);
        outStream.printf("Configured Nucleus to deploy components %s and %s %n", GREENGRASS_CLI_COMPONENT_NAME,
                HTTP_DEBUG_VIEW_COMPONENT_NAME);
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
