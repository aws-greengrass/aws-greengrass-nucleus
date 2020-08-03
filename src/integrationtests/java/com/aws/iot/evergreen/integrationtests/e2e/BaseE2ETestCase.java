/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.AWSEvergreenClientBuilder;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentResult;
import com.amazonaws.services.evergreen.model.DeploymentPolicies;
import com.amazonaws.services.evergreen.model.DeploymentSafetyPolicy;
import com.amazonaws.services.evergreen.model.FailureHandlingPolicy;
import com.amazonaws.services.evergreen.model.ForbiddenException;
import com.amazonaws.services.evergreen.model.InvalidInputException;
import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationRequest;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.ResourceAlreadyExistException;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.amazonaws.services.evergreen.model.SetConfigurationResult;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceHelper;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.tes.CredentialRequestHandler;
import com.aws.iot.evergreen.tes.TokenExchangeService;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.IamSdkClientFactory;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.AttachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyRequest;
import software.amazon.awssdk.services.iam.model.CreatePolicyResponse;
import software.amazon.awssdk.services.iam.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iam.model.DetachRolePolicyRequest;
import software.amazon.awssdk.services.iam.model.EntityAlreadyExistsException;
import software.amazon.awssdk.services.iam.model.NoSuchEntityException;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.DeleteRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.GREENGRASS_SERVICE_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base class for Evergreen E2E tests, with the following functionality:
 *  * Bootstrap one IoT thing group and one IoT thing, and add thing to the group.
 *  * Manages integration points and API calls to Evergreen cloud services in Beta stage.
 */
@ExtendWith(EGExtension.class)
public class BaseE2ETestCase implements AutoCloseable {
    protected static final String FCS_GAMMA_ENDPOINT = "https://bp5p2uvbx6.execute-api.us-east-1.amazonaws.com/Gamma";
    protected static final Region GAMMA_REGION = Region.US_EAST_1;
    protected static final String THING_GROUP_TARGET_TYPE = "thinggroup";
    private static final String TES_ROLE_NAME = "E2ETestsTesRole" + UUID.randomUUID().toString();
    protected static final String TES_ROLE_ALIAS_NAME = "E2ETestsTesRoleAlias" + UUID.randomUUID().toString();
    private static final String TES_ROLE_POLICY_NAME = "E2ETestsTesRolePolicy" + UUID.randomUUID().toString();
    private static final String TES_ROLE_POLICY_DOCUMENT = "{\n"
            + "    \"Version\": \"2012-10-17\",\n"
            + "    \"Statement\": [\n"
            + "        {\n"
            + "            \"Effect\": \"Allow\",\n"
            + "            \"Action\": [\n"
            + "                \"greengrass:*\",\n"
            + "                \"s3:Get*\",\n"
            + "                \"s3:List*\"\n"
            + "            ],\n"
            + "            \"Resource\": \"*\"\n"
            + "        }\n"
            + "    ]\n"
            + "}";
    protected static final String TEST_COMPONENT_ARTIFACTS_S3_BUCKET_PREFIX = "eg-e2e-test-artifacts";
    protected static final String TEST_COMPONENT_ARTIFACTS_S3_BUCKET =
            TEST_COMPONENT_ARTIFACTS_S3_BUCKET_PREFIX + UUID.randomUUID().toString();

    protected static final Logger logger = LogManager.getLogger(BaseE2ETestCase.class);

    private static final String testComponentSuffix = "_" + UUID.randomUUID().toString();
    protected static String tesRolePolicyArn;

    protected final Set<String> createdIotJobIds = new HashSet<>();
    protected final Set<String> createdThingGroups = new HashSet<>();
    protected DeviceProvisioningHelper.ThingInfo thingInfo;
    protected String thingGroupName;
    protected CreateThingGroupResponse thingGroupResp;

    protected DeviceProvisioningHelper deviceProvisioningHelper =
            new DeviceProvisioningHelper(GAMMA_REGION.toString(), System.out);

    @TempDir
    protected static Path tempRootDir;

    protected static Path e2eTestPkgStoreDir;

    protected static PackageStore e2eTestPackageStore;

    protected Kernel kernel;

    protected static final IotClient iotClient = IotSdkClientFactory
            .getIotClient(GAMMA_REGION.toString(), Collections.singleton(InvalidRequestException.class));
    private static AWSEvergreen fcsClient;
    protected static final AWSEvergreen cmsClient =
            AWSEvergreenClientBuilder.standard().withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(GREENGRASS_SERVICE_ENDPOINT, GAMMA_REGION.toString())).build();
    protected static final IamClient iamClient = IamSdkClientFactory.getIamClient();
    protected static final S3Client s3Client = S3Client.builder().region(GAMMA_REGION).build();

    private static final PackageIdentifier[] componentsWithArtifactsInGG =
            {createPackageIdentifier("CustomerApp", new Semver("1.0.0")),
                    createPackageIdentifier("CustomerApp", new Semver("0.9.0")),
                    createPackageIdentifier("CustomerApp", new Semver("0.9.1")),
                    createPackageIdentifier("SomeService", new Semver("1.0.0")),
                    createPackageIdentifier("SomeOldService", new Semver("0.9.0")),
                    createPackageIdentifier("GreenSignal", new Semver("1.0.0")),
                    createPackageIdentifier("RedSignal", new Semver("1.0.0")),
                    createPackageIdentifier("YellowSignal", new Semver("1.0.0")),
                    createPackageIdentifier("Mosquitto", new Semver("1.0.0")),
                    createPackageIdentifier("Mosquitto", new Semver("0.9.0")),
                    createPackageIdentifier("KernelIntegTest", new Semver("1.0.0")),
                    createPackageIdentifier("KernelIntegTestDependency", new Semver("1.0.0")),
                    createPackageIdentifier("Log", new Semver("2.0.0")),
                    createPackageIdentifier("NonDisruptableService", new Semver("1.0.0")),
                    createPackageIdentifier("NonDisruptableService", new Semver("1.0.1"))};
    private static final PackageIdentifier[] componentsWithArtifactsInS3 =
            {createPackageIdentifier("AppWithS3Artifacts", new Semver("1.0.0"))};

    @BeforeAll
    static void beforeAll() throws Exception {
        initializePackageStore();

        uploadTestComponentsToCms(componentsWithArtifactsInGG);
        uploadComponentArtifactsToGG(componentsWithArtifactsInGG);
        commitTestComponentsToCms(componentsWithArtifactsInGG);

        // Self hosted artifacts must exist in S3 before creating a component version
        createS3BucketsForTestComponentArtifacts();
        uploadComponentArtifactToS3(componentsWithArtifactsInS3);
        uploadTestComponentsToCms(componentsWithArtifactsInS3);
        commitTestComponentsToCms(componentsWithArtifactsInS3);

    }

    @AfterAll
    static void afterAll() {
        try {
            List<PackageIdentifier> allComponents = new ArrayList<>(Arrays.asList(componentsWithArtifactsInGG));
            allComponents.addAll(Arrays.asList(componentsWithArtifactsInS3));
            for (PackageIdentifier component : allComponents) {
                DeleteComponentResult result = GreengrassPackageServiceHelper
                        .deleteComponent(cmsClient, component.getName(), component.getVersion().toString());
                assertEquals(200, result.getSdkHttpMetadata().getHttpStatusCode());
            }
        } finally {
            cleanUpTestComponentArtifactsFromS3();
            cleanUpTesRoleAndAlias();
        }
    }

    protected BaseE2ETestCase() {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        thingGroupResp = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        thingGroupName = thingGroupResp.thingGroupName();
        createdThingGroups.add(thingGroupName);
    }

    protected void initKernel() throws IOException, DeviceConfigurationException, InterruptedException {
        kernel = new Kernel().parseArgs("-r", tempRootDir.toAbsolutePath().toString());
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, GAMMA_REGION.toString());
        setupTesRoleAndAlias();
    }

    private static void initializePackageStore() throws Exception {
        Path localStoreContentPath = Paths.get(BaseE2ETestCase.class.getResource("local_store_content").getPath());

        e2eTestPkgStoreDir = tempRootDir.resolve("eteTestPkgStore");
        // copy to tmp directory
        FileUtils.copyDirectory(localStoreContentPath.toFile(), e2eTestPkgStoreDir.toFile());

        e2eTestPackageStore = new PackageStore(e2eTestPkgStoreDir);
    }

    /**
     * Load recipes from local store and publish components to CMS.
     * Directory tree layout should follow the local component store. e.g.
     * src/integrationtests/resources/com/aws/iot/evergreen/integrationtests/e2e
     * └── local_store_content
     *     ├── artifacts
     *     │  └── KernelIntegTest
     *     │      └── 1.0.0
     *     │          └── kernel_integ_test_artifact.txt
     *     └── recipes
     *         ├── KernelIntegTest-1.0.0.yaml
     *         └── KernelIntegTestDependency-1.0.0.yaml
     *
     * @param pkgIds list of component identifiers
     */
    private static void uploadTestComponentsToCms(PackageIdentifier... pkgIds) throws IOException, PackagingException {
        List<String> errors = new ArrayList<>();
        for (PackageIdentifier pkgId : pkgIds) {
            try {
                draftComponent(pkgId);
            } catch (ResourceAlreadyExistException e) {
                // Don't fail the test if the component exists
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            logger.atWarn().kv("errors", errors).log("Ignore errors if a component already exists");
        }
    }

    private static void commitTestComponentsToCms(PackageIdentifier... pkgIds) {
        List<String> errors = new ArrayList<>();
        for (PackageIdentifier pkgId : pkgIds) {
            try {
                GreengrassPackageServiceHelper
                        .commitComponent(cmsClient, pkgId.getName(), pkgId.getVersion().toString());
            } catch (InvalidInputException e) {
                // Don't fail the test if the component is already committed
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            logger.atWarn().kv("errors", errors).log("Ignore errors if a component already exists");
        }
    }

    private static PackageIdentifier getLocalPackageIdentifier(PackageIdentifier pkgIdCloud) {
        return new PackageIdentifier(removeTestComponentNameCloudSuffix(pkgIdCloud.getName()),
                pkgIdCloud.getVersion(), pkgIdCloud.getScope());
    }

    private static void draftComponent(PackageIdentifier pkgIdCloud) throws IOException {
        PackageIdentifier pkgIdLocal = getLocalPackageIdentifier(pkgIdCloud);
        Path testRecipePath = e2eTestPackageStore.resolveRecipePath(pkgIdLocal);

        // update recipe
        String content = new String(Files.readAllBytes(testRecipePath), StandardCharsets.UTF_8);
        Set<String> componentNameSet = Arrays.stream(componentsWithArtifactsInGG)
                .map(component -> component.getName()).collect(Collectors.toSet());
        componentNameSet.addAll(Arrays.stream(componentsWithArtifactsInS3)
                .map(component -> component.getName()).collect(Collectors.toSet()));

        for (String cloudPkgName: componentNameSet) {
            String localPkgName = removeTestComponentNameCloudSuffix(cloudPkgName);
            content = content.replaceAll("\\{\\{" + localPkgName + "}}", cloudPkgName);
            content = content.replaceAll("\\{\\{" + TEST_COMPONENT_ARTIFACTS_S3_BUCKET_PREFIX + "}}", TEST_COMPONENT_ARTIFACTS_S3_BUCKET);
        }

        testRecipePath = e2eTestPackageStore.resolveRecipePath(pkgIdCloud);

        Files.write(testRecipePath, content.getBytes(StandardCharsets.UTF_8));

        CreateComponentResult createComponentResult = GreengrassPackageServiceHelper.createComponent(cmsClient,
                testRecipePath);
        assertEquals("DRAFT", createComponentResult.getStatus());
        assertEquals(pkgIdCloud.getName(), createComponentResult.getComponentName(), createComponentResult.toString());
        assertEquals(pkgIdCloud.getVersion().toString(), createComponentResult.getComponentVersion());
    }

    protected static void uploadComponentArtifactsToGG(PackageIdentifier... pkgIds) throws IOException {
        List<String> errors = new ArrayList<>();
        for (PackageIdentifier pkgId : pkgIds) {
            PackageIdentifier pkgIdLocal = getLocalPackageIdentifier(pkgId);
            Path artifactDirPath = e2eTestPackageStore.resolveArtifactDirectoryPath(pkgIdLocal);
            File[] artifactFiles = artifactDirPath.toFile().listFiles();
            if (artifactFiles == null) {
                logger.atInfo().kv("component", pkgIdLocal).kv("artifactPath", artifactDirPath.toAbsolutePath())
                        .log("Skip artifact upload. No artifacts found");
            } else {
                for (File artifact : artifactFiles) {
                    try {
                        GreengrassPackageServiceHelper
                                .createAndUploadComponentArtifact(cmsClient, artifact, pkgId.getName(),
                                        pkgId.getVersion().toString());
                    } catch (InvalidInputException | ForbiddenException e) {
                        // Don't fail the test if the component is already committed
                        errors.add(e.getMessage());
                    }
                }
                if (!errors.isEmpty()) {
                    logger.atWarn().kv("errors", errors).log("Ignore errors if a component already exists");
                }
            }
        }
    }

    protected static void createS3BucketsForTestComponentArtifacts() {
        try {
            s3Client.createBucket(
                    CreateBucketRequest.builder().bucket(TEST_COMPONENT_ARTIFACTS_S3_BUCKET).build());
        } catch (BucketAlreadyExistsException e) {
            logger.atError().setCause(e).log("Bucket name is taken, please retry the tests");
        } catch (BucketAlreadyOwnedByYouException e) {
            // No-op if bucket exists
        }
    }

    // TODO : Fast follow item to change all e2e tests to upload artifacts to S3
    //  instead of the component management service
    protected static void uploadComponentArtifactToS3(PackageIdentifier... pkgIds) {
        for (PackageIdentifier pkgId : pkgIds) {
            PackageIdentifier pkgIdLocal = getLocalPackageIdentifier(pkgId);
            Path artifactDirPath = e2eTestPackageStore.resolveArtifactDirectoryPath(pkgIdLocal);
            File[] artifactFiles = artifactDirPath.toFile().listFiles();
            if (artifactFiles == null) {
                logger.atInfo().kv("component", pkgIdLocal).kv("artifactPath", artifactDirPath.toAbsolutePath())
                        .log("Skip artifact upload. No artifacts found");
            } else {
                for (File artifact : artifactFiles) {
                    try {
                        // Path is <bucket>/<component_name>-<component>_<component_version>/<filename>
                        s3Client.putObject(PutObjectRequest.builder().bucket(TEST_COMPONENT_ARTIFACTS_S3_BUCKET)
                                .key(pkgIdLocal.getName() + "-" + pkgIdLocal.getVersion().toString() + "/" + artifact
                                        .getName()).build(), RequestBody.fromFile(artifact));
                    } catch (S3Exception e) {
                        logger.atError().setCause(e).log("Could not upload artifacts to S3");
                    }
                }
            }

        }
    }

    protected static void cleanUpTestComponentArtifactsFromS3() {
        try {
            ListObjectsResponse objectsInArtifactsBucket = s3Client.listObjects(
                    ListObjectsRequest.builder().bucket(TEST_COMPONENT_ARTIFACTS_S3_BUCKET).build());
            for (S3Object artifact : objectsInArtifactsBucket.contents()) {
                s3Client.deleteObject(
                        DeleteObjectRequest.builder().bucket(TEST_COMPONENT_ARTIFACTS_S3_BUCKET).key(artifact.key())
                                .build());
            }
            s3Client.deleteBucket(DeleteBucketRequest.builder().bucket(TEST_COMPONENT_ARTIFACTS_S3_BUCKET).build());
        } catch (NoSuchKeyException | NoSuchBucketException e) {
            // No-op
        } catch (S3Exception e) {
            logger.atInfo().addKeyValue("error-message", e.getMessage())
                    .log("Could not clean up test component artifacts");
        }
    }

    protected static synchronized AWSEvergreen getFcsClient() {
        if (fcsClient == null) {
            AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(
                    FCS_GAMMA_ENDPOINT, GAMMA_REGION.toString());
            fcsClient = AWSEvergreenClientBuilder.standard()
                    .withEndpointConfiguration(endpointConfiguration).build();
        }
        return fcsClient;
    }

    @SuppressWarnings("PMD.LinguisticNaming")
    protected PublishConfigurationResult setAndPublishFleetConfiguration(SetConfigurationRequest setRequest) {
        AWSEvergreen client = getFcsClient();

        // update package name with random suffix to avoid conflict in cloud
        Map<String, PackageMetaData> updatedPkgMetadata = new HashMap<>();
        setRequest.getPackages().forEach((key, val) -> {
            updatedPkgMetadata.put(getTestComponentNameInCloud(key), val);
        });
        setRequest.setPackages(updatedPkgMetadata);

        // set default value
        if (setRequest.getDeploymentPolicies() == null) {
            setRequest.withDeploymentPolicies(new DeploymentPolicies()
                .withDeploymentSafetyPolicy(DeploymentSafetyPolicy.CHECK_SAFETY)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING));
        }

        logger.atInfo().kv("setRequest", setRequest).log();
        SetConfigurationResult setResult = client.setConfiguration(setRequest);
        logger.atInfo().kv("setResult", setResult).log();

        PublishConfigurationRequest publishRequest = new PublishConfigurationRequest()
                .withTargetName(setRequest.getTargetName())
                .withTargetType(setRequest.getTargetType())
                .withRevisionId(setResult.getRevisionId());
        logger.atInfo().kv("publishRequest", publishRequest).log();
        PublishConfigurationResult publishResult = client.publishConfiguration(publishRequest);
        logger.atInfo().kv("publishResult", publishResult).log();
        createdIotJobIds.add(publishResult.getJobId());
        return publishResult;
    }

    protected void cleanup() {
        deviceProvisioningHelper.cleanThing(iotClient, thingInfo);
        createdThingGroups.forEach(thingGroup-> IotJobsUtils.cleanThingGroup(iotClient, thingGroupName));
        createdThingGroups.clear();
        createdIotJobIds.forEach(jobId -> IotJobsUtils.cleanJob(iotClient, jobId));
        createdIotJobIds.clear();
        if (kernel == null || kernel.getConfigPath() == null) {
            return;
        }
        for (File subFile : kernel.getConfigPath().toFile().listFiles()) {
            boolean result = subFile.delete();
            if (!result) {
                logger.atWarn().kv("fileName", subFile.toString()).log("Fail to delete file in cleanup.");
            }
        }
    }

    protected void setupTesRoleAndAlias() throws InterruptedException {
        try {
            deviceProvisioningHelper
                    .setupIoTRoleForTes(TES_ROLE_NAME, TES_ROLE_ALIAS_NAME, thingInfo.getCertificateArn());
            deviceProvisioningHelper.updateKernelConfigWithTesRoleInfo(kernel, TES_ROLE_ALIAS_NAME);

            CreatePolicyResponse createPolicyResponse = iamClient.createPolicy(
                    CreatePolicyRequest.builder().policyName(TES_ROLE_POLICY_NAME)
                            .policyDocument(TES_ROLE_POLICY_DOCUMENT)
                            .description("Defines permissions to access AWS services for E2E test device TES role")
                            .build());
            tesRolePolicyArn = createPolicyResponse.policy().arn();
            iamClient.attachRolePolicy(AttachRolePolicyRequest.builder().roleName(TES_ROLE_NAME)
                    .policyArn(tesRolePolicyArn).build());
        } catch (EntityAlreadyExistsException e) {
            // No-op if resources already exist
        }

        // Force context to create TES now to that it subscribes to the role alias changes
        kernel.getContext().get(TokenExchangeService.class);

        while(!(new String(kernel.getContext().get(CredentialRequestHandler.class).getCredentialsBypassCache(),
                StandardCharsets.UTF_8).toLowerCase().contains("accesskeyid"))) {
            logger.atInfo().kv("roleAlias", TES_ROLE_ALIAS_NAME)
                    .log("Waiting 5 seconds for TES to get credentials that work");
            Thread.sleep(5_000);
        }
    }

    protected static void cleanUpTesRoleAndAlias() {
        try {
            iotClient.deleteRoleAlias(DeleteRoleAliasRequest.builder().roleAlias(TES_ROLE_ALIAS_NAME).build());
            iamClient.detachRolePolicy(DetachRolePolicyRequest.builder().roleName(TES_ROLE_NAME).policyArn(tesRolePolicyArn).build());
            iamClient.deleteRole(DeleteRoleRequest.builder().roleName(TES_ROLE_NAME).build());
            iamClient.deletePolicy(DeletePolicyRequest.builder().policyArn(tesRolePolicyArn).build());
        } catch (ResourceNotFoundException | NoSuchEntityException e) {
            logger.atInfo().addKeyValue("error-message", e.getMessage()).log("Could not clean up TES resources");
        }
    }

    @Override
    public void close() throws Exception {
        if (fcsClient != null) {
            fcsClient.shutdown();
        }
        cmsClient.shutdown();
        iotClient.close();
        iamClient.close();
        s3Client.close();
    }

    private static PackageIdentifier createPackageIdentifier(String name, Semver version) {
        return new PackageIdentifier(getTestComponentNameInCloud(name), version, "private");
    }

    protected static String getTestComponentNameInCloud(String name) {
        if (name.endsWith(testComponentSuffix)) {
            return name;
        }
        return name + testComponentSuffix;
    }

    protected static String removeTestComponentNameCloudSuffix(String name) {
        int index = name.lastIndexOf(testComponentSuffix);
        if (index > 0) {
            return name.substring(0, index);
        }
        return name;
    }

    public EvergreenService getCloudDeployedComponent(String name) throws ServiceLoadException {
        return kernel.locate(getTestComponentNameInCloud(name));
    }

}
