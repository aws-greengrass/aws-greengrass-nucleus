/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.easysetup;

import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.CommitableFile;
import com.aws.greengrass.util.EncryptionUtils;
import com.aws.greengrass.util.IamSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory.EnvironmentStage;
import com.aws.greengrass.util.ProxyUtils;
import com.aws.greengrass.util.RegionUtils;
import com.aws.greengrass.util.StsSdkClientFactory;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.greengrassv2.model.ComponentDeploymentSpecification;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicy;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentFailureHandlingPolicy;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentPolicies;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyResponse;
import software.amazon.awssdk.services.iam.model.CreateRoleRequest;
import software.amazon.awssdk.services.iam.model.GetRoleRequest;
import software.amazon.awssdk.services.iam.model.IamException;
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
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;
import software.amazon.awssdk.utils.ImmutableMap;
import software.amazon.awssdk.utils.IoUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Provision a device by registering as an IoT thing, creating roles and template first party components.
 */
@Getter
public class DeviceProvisioningHelper {
    private static final String GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_SUFFIX = "Access";
    private static final String GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_DOCUMENT =
            "{\n" + "    \"Version\": \"2012-10-17\",\n"
                    + "    \"Statement\": [\n"
                    + "        {\n"
                    + "            \"Effect\": \"Allow\",\n"
                    + "            \"Action\": [\n"
                    + "                \"logs:CreateLogGroup\",\n"
                    + "                \"logs:CreateLogStream\",\n"
                    + "                \"logs:PutLogEvents\",\n"
                    + "                \"logs:DescribeLogStreams\",\n"
                    + "                \"s3:GetBucketLocation\"\n"
                    + "            ],\n"
                    + "            \"Resource\": \"*\"\n"
                    + "        }\n"
                    + "    ]\n"
                    + "}";
    private static final String ROOT_CA_URL = "https://www.amazontrust.com/repository/AmazonRootCA1.pem";
    private static final String IOT_ROLE_POLICY_NAME_PREFIX = "GreengrassTESCertificatePolicy";
    private static final String GREENGRASS_CLI_COMPONENT_NAME = "aws.greengrass.Cli";
    private static final String INITIAL_DEPLOYMENT_NAME_FORMAT = "Deployment for %s";
    private static final String IAM_POLICY_ARN_FORMAT = "arn:%s:iam::%s:policy/%s";
    private static final String MANAGED_IAM_POLICY_ARN_FORMAT = "arn:%s:iam::aws:policy/%s";

    private static final String E2E_TESTS_POLICY_NAME_PREFIX = "E2ETestsIotPolicy";
    private static final String E2E_TESTS_THING_NAME_PREFIX = "E2ETestsIotThing";

    private final Map<EnvironmentStage, String> tesServiceEndpoints = ImmutableMap.of(
            EnvironmentStage.PROD, "credentials.iot.amazonaws.com",
            EnvironmentStage.GAMMA, "credentials.iot.test.amazonaws.com",
            EnvironmentStage.BETA, "credentials.iot.test.amazonaws.com"
    );

    private final PrintStream outStream;
    private final IotClient iotClient;
    private final IamClient iamClient;
    private final StsClient stsClient;
    private final GreengrassV2Client greengrassClient;
    private EnvironmentStage envStage = EnvironmentStage.PROD;
    private boolean thingGroupExists = false;
    private String thingGroupArn;

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
        this.iamClient = IamSdkClientFactory.getIamClient(awsRegion);
        this.stsClient = StsSdkClientFactory.getStsClient(awsRegion);
        this.greengrassClient = GreengrassV2Client.builder().endpointOverride(
                        URI.create(RegionUtils.getGreengrassControlPlaneEndpoint(awsRegion, this.envStage)))
                .region(Region.of(awsRegion))
                .build();
    }

    /**
     * Constructor for unit tests.
     *
     * @param outStream        stream to provide customer feedback
     * @param iotClient        iot client
     * @param iamClient        iam client
     * @param stsClient        sts client
     * @param greengrassClient Greengrass client
     */
    DeviceProvisioningHelper(PrintStream outStream, IotClient iotClient, IamClient iamClient,
                             StsClient stsClient, GreengrassV2Client greengrassClient) {
        this.outStream = outStream;
        this.iotClient = iotClient;
        this.iamClient = iamClient;
        this.stsClient = stsClient;
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
     * @param client     iotClient to use
     * @param policyName policyName
     * @param thingName  thingName
     * @return created thing info
     */
    public ThingInfo createThing(IotClient client, String policyName, String thingName) {
        // Find or create IoT policy
        try {
            client.getPolicy(GetPolicyRequest.builder().policyName(policyName).build());
            outStream.printf("Found IoT policy \"%s\", reusing it%n", policyName);
        } catch (ResourceNotFoundException e) {
            outStream.printf("Creating new IoT policy \"%s\"%n", policyName);
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
        outStream.printf("Creating IoT Thing \"%s\"...%n", thingName);
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
     *
     * To support HTTPS proxies and other custom truststore configurations, append to the file if it exists.
     */
    private void downloadRootCAToFile(File f) {
        if (f.exists()) {
            outStream.printf("Root CA file found at \"%s\". Contents will be preserved.%n", f);
        }
        outStream.printf("Downloading Root CA from \"%s\"%n", ROOT_CA_URL);
        try {
            downloadFileFromURL(ROOT_CA_URL, f);
            removeDuplicateCertificates(f);
        } catch (IOException e) {
            // Do not block as the root CA file may have been manually provisioned
            outStream.printf("Failed to download Root CA - %s%n", e);
        }
    }

    private void removeDuplicateCertificates(File f) {
        try {
            String certificates = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Set<String> uniqueCertificates =
                    Arrays.stream(certificates.split(EncryptionUtils.CERTIFICATE_PEM_HEADER))
                            .map(s -> s.trim())
                            .collect(Collectors.toSet());

            try (BufferedWriter bw = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
                for (String certificate : uniqueCertificates) {
                    if (certificate.length() > 0) {
                        bw.write(EncryptionUtils.CERTIFICATE_PEM_HEADER);
                        bw.write("\n");
                        bw.write(certificate);
                        bw.write("\n");
                    }
                }
            }
        } catch (IOException e) {
            outStream.printf("Failed to remove duplicate certificates - %s%n", e);
        }
    }

    /*
     * Download content from a URL to a local file.
     */
    @SuppressWarnings("PMD.AvoidFileStream")
    private void downloadFileFromURL(String url, File f) throws IOException {
        SdkHttpFullRequest request = SdkHttpFullRequest.builder()
                .uri(URI.create(url))
                .method(SdkHttpMethod.GET)
                .build();

        HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                .request(request)
                .build();

        try (SdkHttpClient client = getSdkHttpClient()) {
            HttpExecuteResponse executeResponse = client.prepareRequest(executeRequest).call();

            int responseCode = executeResponse.httpResponse().statusCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw new IOException("Received invalid response code: " + responseCode);
            }

            try (InputStream inputStream = executeResponse.responseBody().get();
                 OutputStream outputStream = Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE,
                         StandardOpenOption.APPEND)) {
                IoUtils.copy(inputStream, outputStream);
            }
        }
    }

    private SdkHttpClient getSdkHttpClient() {
        return ProxyUtils.getSdkHttpClient();
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
        Path ipcSocketPath = rootDir.resolve("ipc.socket");

        downloadRootCAToFile(caFilePath.toFile());
        try (CommitableFile cf = CommitableFile.of(privKeyFilePath, true)) {
            cf.write(thing.keyPair.privateKey().getBytes(StandardCharsets.UTF_8));
        }
        try (CommitableFile cf = CommitableFile.of(certFilePath, true)) {
            cf.write(thing.certificatePem.getBytes(StandardCharsets.UTF_8));
        }

        new DeviceConfiguration(kernel, thing.thingName, thing.dataEndpoint, thing.credEndpoint,
                privKeyFilePath.toString(), certFilePath.toString(), caFilePath.toString(), ipcSocketPath.toString(), awsRegion, roleAliasName);
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
            outStream.printf("TES role alias \"%s\" does not exist, creating new alias...%n", roleAliasName);

            // Get IAM role arn in order to attach an alias to it
            String roleArn;
            try {
                GetRoleRequest getRoleRequest = GetRoleRequest.builder().roleName(roleName).build();
                roleArn = iamClient.getRole(getRoleRequest).role().arn();
            } catch (NoSuchEntityException | ResourceNotFoundException rnfe) {
                outStream.printf("TES role \"%s\" does not exist, creating role...%n", roleName);
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
            outStream.printf("IoT role policy \"%s\" for TES Role alias not exist, creating policy...%n",
                    iotRolePolicyName);
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
     * @param roleName  name of target role
     * @param awsRegion aws region
     * @return ARN of created policy
     */
    public Optional<String> createAndAttachRolePolicy(String roleName, Region awsRegion) {
        return createAndAttachRolePolicy(roleName, roleName + GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_SUFFIX,
                GG_TOKEN_EXCHANGE_ROLE_ACCESS_POLICY_DOCUMENT, awsRegion);
    }

    /**
     * Creates IAM policy using specified name and document. Attach the policy to given IAM role name.
     *
     * @param roleName           name of target role
     * @param rolePolicyName     name of policy to create and attach
     * @param rolePolicyDocument document of policy to create and attach
     * @param awsRegion          aws region
     * @return ARN of created policy
     */
    public Optional<String> createAndAttachRolePolicy(String roleName, String rolePolicyName, String rolePolicyDocument,
                                                      Region awsRegion) {
        Optional<String> tesRolePolicyArnOptional = getPolicyArn(rolePolicyName, awsRegion);
        if (tesRolePolicyArnOptional.isPresent()) {
            outStream.printf("IAM policy named \"%s\" already exists. Please attach it to the IAM role if not "
                    + "already%n", rolePolicyName);
            return tesRolePolicyArnOptional;
        } else {
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
        }
    }

    private Optional<String> getPolicyArn(String policyName, Region awsRegion) {
        String partition = awsRegion.metadata().partition().id();
        try {
            // Check if a managed policy exists with the name
            return Optional.of(iamClient.getPolicy(software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                    .policyArn(String.format(MANAGED_IAM_POLICY_ARN_FORMAT, partition, policyName)).build()).policy()
                    .arn());
        } catch (NoSuchEntityException mnf) {
            outStream.println("No managed IAM policy found, looking for user defined policy...");
        } catch (IamException e) {
            if (e.getMessage().contains("not authorized to perform")) {
                outStream.printf("Encountered error - %s; No permissions to lookup managed policy, "
                        + "looking for a user defined policy...%n", e.getMessage());
            } else {
                outStream.printf("Exiting due to unexpected error while looking up managed policy - %s %n",
                        e.getMessage());
                throw e;
            }
        }
        // Check if a customer policy exists with the name
        try {
            return Optional.of(iamClient.getPolicy(software.amazon.awssdk.services.iam.model.GetPolicyRequest.builder()
                    .policyArn(String.format(IAM_POLICY_ARN_FORMAT, partition, getAccountId(), policyName)).build())
                    .policy().arn());
        } catch (NoSuchEntityException cnf) {
            outStream.println("No IAM policy found, will attempt creating one...");
        } catch (IamException e) {
            if (e.getMessage().contains("not authorized to perform")) {
                outStream.printf(
                        "Encountered error - %s; No permissions to lookup IAM policy, will attempt creating one. If you"
                                + " wish to use an existing policy instead, please make sure the credentials used for "
                                + "setup have iam::getPolicy permissions for the policy resource and retry...%n",
                        e.getMessage());
            } else {
                outStream.printf("Exiting due to unexpected error while looking up user defined policy - %s%n",
                        e.getMessage());
                throw e;
            }
        }
        return Optional.empty();
    }

    private String getAccountId() {
        return stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
    }

    /**
     * Add an existing Thing into a Thing Group which may or may not exist,
     * creates thing group if it doesn't exist.
     *
     * @param iotClient      client
     * @param thingName      thing name
     * @param thingGroupName group to add the thing into
     */
    public void addThingToGroup(IotClient iotClient, String thingName, String thingGroupName) {
        try {
            thingGroupArn = iotClient
                    .describeThingGroup(DescribeThingGroupRequest.builder().thingGroupName(thingGroupName).build())
                    .thingGroupArn();
            thingGroupExists = true;
            outStream.printf("IoT Thing Group \"%s\" already existed, reusing it%n", thingGroupName);
        } catch (ResourceNotFoundException e) {
            thingGroupArn =
                    iotClient.createThingGroup(CreateThingGroupRequest.builder().thingGroupName(thingGroupName).build())
                            .thingGroupArn();
        }
        iotClient.addThingToThingGroup(
                AddThingToThingGroupRequest.builder().thingName(thingName).thingGroupName(thingGroupName).build());
    }

    /**
     * Creates an initial deployment to deploy dev tools like the Greengrass CLI component.
     *
     * @param thingInfo thing info for the device
     * @param thingGroupName thing group name
     * @param cliVersion CLI version to install
     */
    public void createInitialDeploymentIfNeeded(ThingInfo thingInfo, String thingGroupName, String cliVersion) {
        if (Utils.isNotEmpty(thingGroupName) && thingGroupExists) {
            // Skip creating a dev tools deployment to existing thing group since it can remove existing components if
            // and can add to cost because it will be applied to all existing devices in the group
            outStream.println(
                    "Thing group exists, it could have existing deployment and devices, hence NOT creating deployment "
                            + "for Greengrass first party dev tools, please manually create a deployment if you wish "
                            + "to");
            return;
        }

        CreateDeploymentRequest.Builder deploymentRequest = CreateDeploymentRequest.builder().deploymentPolicies(
                DeploymentPolicies.builder().configurationValidationPolicy(
                        DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(60).build())
                        .componentUpdatePolicy(DeploymentComponentUpdatePolicy.builder()
                                .action(DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS)
                                .timeoutInSeconds(60).build())
                        .failureHandlingPolicy(DeploymentFailureHandlingPolicy.DO_NOTHING).build());

        if (Utils.isNotEmpty(thingGroupName)) {
            outStream.println("Creating a deployment for Greengrass first party components to the thing group");
            deploymentRequest.targetArn(thingGroupArn)
                    .deploymentName(String.format(INITIAL_DEPLOYMENT_NAME_FORMAT, thingGroupName));
        } else {
            outStream.println("Creating a deployment for Greengrass first party components to the device");
            deploymentRequest.targetArn(thingInfo.thingArn)
                    .deploymentName(String.format(INITIAL_DEPLOYMENT_NAME_FORMAT, thingInfo.thingName));
        }

        deploymentRequest.components(Utils.immutableMap(GREENGRASS_CLI_COMPONENT_NAME,
                ComponentDeploymentSpecification.builder().componentVersion(cliVersion).build()));

        greengrassClient.createDeployment(deploymentRequest.build());
        outStream.printf("Configured Nucleus to deploy %s component%n", GREENGRASS_CLI_COMPONENT_NAME);
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
