/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.AWSEvergreenClientBuilder;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicy;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.amazonaws.services.evergreen.model.ConfigurationValidationPolicy;
import com.amazonaws.services.evergreen.model.CreateComponentResult;
import com.amazonaws.services.evergreen.model.DeleteComponentResult;
import com.amazonaws.services.evergreen.model.DeploymentPolicies;
import com.amazonaws.services.evergreen.model.FailureHandlingPolicy;
import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationRequest;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.ResourceAlreadyExistsException;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.amazonaws.services.evergreen.model.SetConfigurationResult;
import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.easysetup.DeviceProvisioningHelper;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.tes.CredentialRequestHandler;
import com.aws.greengrass.tes.TokenExchangeService;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.IamSdkClientFactory;
import com.aws.greengrass.util.IotSdkClientFactory;
import com.aws.greengrass.util.NucleusPaths;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.DeleteConflictException;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.easysetup.DeviceProvisioningHelper.STAGE_TO_ENDPOINT_FORMAT;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Base class for E2E tests, with the following functionality:
 *  * Bootstrap one IoT thing group and one IoT thing, and add thing to the group.
 *  * Manages integration points and API calls to Greengrass cloud services in Beta stage.
 */
@ExtendWith(GGExtension.class)
public class BaseE2ETestCase implements AutoCloseable {
    protected static final Region GAMMA_REGION = Region.US_EAST_1;
    protected static final String THING_GROUP_TARGET_TYPE = "thinggroup";
    protected static final String THING_TARGET_TYPE = "thing";
    private static final String TES_ROLE_NAME = "E2ETestsTesRole";
    protected static final String TES_ROLE_ALIAS_NAME = "E2ETestsTesRoleAlias";
    private static final String TES_ROLE_POLICY_NAME = "E2ETestsTesRolePolicy";
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
    protected static Optional<String> tesRolePolicyArn;
    protected static final IotSdkClientFactory.EnvironmentStage envStage = IotSdkClientFactory.EnvironmentStage.GAMMA;

    protected final Set<String> createdIotJobIds = new HashSet<>();
    protected final Set<String> createdThingGroups = new HashSet<>();
    protected DeviceProvisioningHelper.ThingInfo thingInfo;
    protected String thingGroupName;
    protected CreateThingGroupResponse thingGroupResp;

    protected DeviceProvisioningHelper deviceProvisioningHelper =
            new DeviceProvisioningHelper(GAMMA_REGION.toString(), envStage.toString(),
                    System.out);

    @TempDir
    protected Path tempRootDir;

    @TempDir
    protected static Path e2eTestPkgStoreDir;

    protected static ComponentStore e2ETestComponentStore;

    protected Kernel kernel;

    protected static IotClient iotClient;

    static {
        try {
            iotClient = IotSdkClientFactory.getIotClient(GAMMA_REGION.toString(),
                        envStage,
                        new HashSet<>(Arrays.asList(InvalidRequestException.class, DeleteConflictException.class)));
        } catch (URISyntaxException e) {
            logger.atError().setCause(e).log("Caught exception while initializing Iot client");
            throw new RuntimeException(e);
        }
    };

    protected static final AWSEvergreen greengrassClient = AWSEvergreenClientBuilder.standard()
                                                                             .withEndpointConfiguration(
                                                                                     new AwsClientBuilder.EndpointConfiguration(
                                                                                             String.format(STAGE_TO_ENDPOINT_FORMAT.get(envStage), GAMMA_REGION.toString()),
                                                                                             GAMMA_REGION.toString()))
                                                                             .build();
    protected static final IamClient iamClient = IamSdkClientFactory.getIamClient();
    protected static final S3Client s3Client = S3Client.builder().region(GAMMA_REGION).build();

    private static final ComponentIdentifier[] componentsWithArtifactsInS3 =
            {createPackageIdentifier("AppWithS3Artifacts", new Semver("1.0.0")),
            createPackageIdentifier("CustomerApp", new Semver("1.0.0")),
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

    @BeforeAll
    static void beforeAll() throws Exception {
        initializePackageStore();

        // Self hosted artifacts must exist in S3 before creating a component version
        createS3BucketsForTestComponentArtifacts();
        uploadComponentArtifactToS3(componentsWithArtifactsInS3);
        uploadTestComponentsToCms(componentsWithArtifactsInS3);
    }

    @AfterAll
    static void afterAll() {
        try {
            List<ComponentIdentifier> allComponents = new ArrayList<>(Arrays.asList(componentsWithArtifactsInS3));
            for (ComponentIdentifier component : allComponents) {
                DeleteComponentResult result = ComponentServiceHelper
                        .deleteComponent(greengrassClient, component.getName(), component.getVersion().toString());
                assertEquals(200, result.getSdkHttpMetadata().getHttpStatusCode());
            }
        } finally {
            cleanUpTestComponentArtifactsFromS3();
        }
    }

    protected BaseE2ETestCase() throws Exception {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        thingGroupResp = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        thingGroupName = thingGroupResp.thingGroupName();
        createdThingGroups.add(thingGroupName);
    }

    public static void setDefaultRunWithUser(Kernel kernel) {
        new DeviceConfiguration(kernel).getRunWithDefaultPosixUser().dflt("nobody");
    }

    protected void initKernel()
            throws IOException, DeviceConfigurationException, InterruptedException, ServiceLoadException {
        kernel = new Kernel().parseArgs("-r", tempRootDir.toAbsolutePath().toString(), "-ar", GAMMA_REGION.toString()
                , "-es", envStage.toString());
        setupTesRoleAndAlias();
        setDefaultRunWithUser(kernel);
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, GAMMA_REGION.toString(),
                TES_ROLE_ALIAS_NAME);
        // Force context to create TES now to that it subscribes to the role alias changes
        kernel.getContext().get(TokenExchangeService.class);
        while (kernel.getContext().get(CredentialRequestHandler.class).getAwsCredentialsBypassCache() == null) {
            logger.atInfo().kv("roleAlias", TES_ROLE_ALIAS_NAME)
                    .log("Waiting 5 seconds for TES to get credentials that work");
            Thread.sleep(5_000);
        }
    }

    private static void initializePackageStore() throws Exception {
        Path localStoreContentPath = Paths.get(BaseE2ETestCase.class.getResource("local_store_content").getPath());

        // copy to tmp directory
        FileUtils.copyDirectory(localStoreContentPath.toFile(), e2eTestPkgStoreDir.toFile());

        NucleusPaths nucleusPaths = new NucleusPaths();
        nucleusPaths.setComponentStorePath(e2eTestPkgStoreDir);
        e2ETestComponentStore = new ComponentStore(nucleusPaths);
    }

    /**
     * Load recipes from local store and publish components to CMS.
     * Directory tree layout should follow the local component store. e.g.
     * src/integrationtests/resources/com/aws/greengrass/integrationtests/e2e
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
    private static void uploadTestComponentsToCms(ComponentIdentifier... pkgIds) throws IOException, PackagingException {
        List<String> errors = new ArrayList<>();
        for (ComponentIdentifier pkgId : pkgIds) {
            try {
                draftComponent(pkgId);
            } catch (ResourceAlreadyExistsException e) {
                // Don't fail the test if the component exists
                errors.add(e.getMessage());
            }
        }
        if (!errors.isEmpty()) {
            logger.atWarn().kv("errors", errors).log("Ignore errors if a component already exists");
        }
    }

    private static ComponentIdentifier getLocalPackageIdentifier(ComponentIdentifier pkgIdCloud) {
        return new ComponentIdentifier(removeTestComponentNameCloudSuffix(pkgIdCloud.getName()),
                pkgIdCloud.getVersion());
    }

    private static void draftComponent(ComponentIdentifier pkgIdCloud) throws IOException {
        ComponentIdentifier pkgIdLocal = getLocalPackageIdentifier(pkgIdCloud);
        Path testRecipePath = e2ETestComponentStore.resolveRecipePath(pkgIdLocal);

        // update recipe
        String content = new String(Files.readAllBytes(testRecipePath), StandardCharsets.UTF_8);
        Set<String> componentNameSet = Arrays.stream(componentsWithArtifactsInS3)
                .map(ComponentIdentifier::getName).collect(Collectors.toSet());

        for (String cloudPkgName: componentNameSet) {
            String localPkgName = removeTestComponentNameCloudSuffix(cloudPkgName);
            content = content.replaceAll("\\{\\{" + localPkgName + "}}", cloudPkgName);
            content = content.replaceAll("\\{\\{" + TEST_COMPONENT_ARTIFACTS_S3_BUCKET_PREFIX + "}}", TEST_COMPONENT_ARTIFACTS_S3_BUCKET);
        }

        testRecipePath = e2ETestComponentStore.resolveRecipePath(pkgIdCloud);

        Files.write(testRecipePath, content.getBytes(StandardCharsets.UTF_8));

        CreateComponentResult createComponentResult =
                ComponentServiceHelper.createComponent(greengrassClient, testRecipePath);
        assertEquals(pkgIdCloud.getName(), createComponentResult.getName(), createComponentResult.toString());
        assertEquals(pkgIdCloud.getVersion().toString(), createComponentResult.getVersion());
    }

    private static void createS3BucketsForTestComponentArtifacts() {
        try {
            s3Client.createBucket(
                    CreateBucketRequest.builder().bucket(TEST_COMPONENT_ARTIFACTS_S3_BUCKET).build());
        } catch (BucketAlreadyExistsException e) {
            logger.atError().setCause(e).log("Bucket name is taken, please retry the tests");
        } catch (BucketAlreadyOwnedByYouException e) {
            // No-op if bucket exists
        }
    }

    private static void uploadComponentArtifactToS3(ComponentIdentifier... pkgIds) throws PackageLoadingException {
        for (ComponentIdentifier pkgId : pkgIds) {
            ComponentIdentifier pkgIdLocal = getLocalPackageIdentifier(pkgId);
            Path artifactDirPath = e2ETestComponentStore.resolveArtifactDirectoryPath(pkgIdLocal);
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

    private static void cleanUpTestComponentArtifactsFromS3() {
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

    @SuppressWarnings("PMD.LinguisticNaming")
    protected PublishConfigurationResult setAndPublishFleetConfiguration(SetConfigurationRequest setRequest) {

        // update package name with random suffix to avoid conflict in cloud
        Map<String, PackageMetaData> updatedPkgMetadata = new HashMap<>();
        setRequest.getPackages().forEach((key, val) -> updatedPkgMetadata.put(getTestComponentNameInCloud(key), val));
        setRequest.setPackages(updatedPkgMetadata);

        // set default value
        if (setRequest.getDeploymentPolicies() == null) {
            setRequest.withDeploymentPolicies(new DeploymentPolicies()
                    .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                    .withComponentUpdatePolicy(
                            new ComponentUpdatePolicy().withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS)
                                    .withTimeout(120))
                    .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING));
        }

        logger.atInfo().kv("setRequest", setRequest).log();
        SetConfigurationResult setResult = greengrassClient.setConfiguration(setRequest);
        logger.atInfo().kv("setResult", setResult).log();

        PublishConfigurationRequest publishRequest = new PublishConfigurationRequest()
                .withTargetName(setRequest.getTargetName())
                .withTargetType(setRequest.getTargetType())
                .withRevisionId(setResult.getRevisionId());
        logger.atInfo().kv("publishRequest", publishRequest).log();
        PublishConfigurationResult publishResult = greengrassClient.publishConfiguration(publishRequest);
        logger.atInfo().kv("publishResult", publishResult).log();
        if (setRequest.getTargetType().equals(THING_GROUP_TARGET_TYPE)) {
            createdIotJobIds.add(publishResult.getJobId());
        }
        return publishResult;
    }

    protected void cleanup() {
        deviceProvisioningHelper.cleanThing(iotClient, thingInfo, false);
        createdThingGroups.forEach(thingGroup-> IotJobsUtils.cleanThingGroup(iotClient, thingGroupName));
        createdThingGroups.clear();
        createdIotJobIds.forEach(jobId -> IotJobsUtils.cleanJob(iotClient, jobId));
        createdIotJobIds.clear();
        if (kernel == null || kernel.getNucleusPaths().configPath() == null) {
            return;
        }
        for (File subFile : kernel.getNucleusPaths().configPath().toFile().listFiles()) {
            boolean result = subFile.delete();
            if (!result) {
                logger.atWarn().kv("fileName", subFile.toString()).log("Fail to delete file in cleanup.");
            }
        }
    }

    protected void setupTesRoleAndAlias() throws InterruptedException {
        deviceProvisioningHelper
                .setupIoTRoleForTes(TES_ROLE_NAME, TES_ROLE_ALIAS_NAME, thingInfo.getCertificateArn());
        if (tesRolePolicyArn == null || !tesRolePolicyArn.isPresent()) {
            tesRolePolicyArn = deviceProvisioningHelper
                    .createAndAttachRolePolicy(TES_ROLE_NAME, TES_ROLE_POLICY_NAME, TES_ROLE_POLICY_DOCUMENT);
        }
    }

    protected static void setDeviceConfig(Kernel kernel, String key, Number value) {
        kernel.getConfig().lookup(SERVICES_NAMESPACE_TOPIC, DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME,
                CONFIGURATION_CONFIG_KEY, key).withValue(value);
    }

    @Override
    public void close() throws Exception {
        greengrassClient.shutdown();
        iotClient.close();
        iamClient.close();
        s3Client.close();
    }

    private static ComponentIdentifier createPackageIdentifier(String name, Semver version) {
        return new ComponentIdentifier(getTestComponentNameInCloud(name), version);
    }

    public static String getTestComponentNameInCloud(String name) {
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

    public GreengrassService getCloudDeployedComponent(String name) throws ServiceLoadException {
        return kernel.locate(getTestComponentNameInCloud(name));
    }

}
