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
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;

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
    protected static final String FCS_BETA_ENDPOINT = "https://aqzw8qdn5l.execute-api.us-east-1.amazonaws.com/Beta";
    protected static final Region BETA_REGION = Region.US_EAST_1;
    protected static final String THING_GROUP_TARGET_TYPE = "thinggroup";

    protected final Logger logger = LogManager.getLogger(this.getClass());
    private static final Logger staticlogger = LogManager.getLogger(BaseE2ETestCase.class);

    private final static String testComponentSuffix = "_" + UUID.randomUUID().toString();

    protected final Set<String> createdIotJobIds = new HashSet<>();
    protected final Set<String> createdThingGroups = new HashSet<>();
    protected DeviceProvisioningHelper.ThingInfo thingInfo;
    protected String thingGroupName;
    protected CreateThingGroupResponse thingGroupResp;
    protected DeviceProvisioningHelper deviceProvisioningHelper =
            new DeviceProvisioningHelper(BETA_REGION.toString(), System.out);

    @TempDir
    protected static Path tempRootDir;

    protected static Path e2eTestPkgStoreDir;

    protected static PackageStore e2eTestPackageStore;

    protected Kernel kernel;

    protected static final IotClient iotClient = IotSdkClientFactory
            .getIotClient(BETA_REGION.toString(), Collections.singleton(InvalidRequestException.class));
    private static AWSEvergreen fcsClient;
    protected static final AWSEvergreen cmsClient =
            AWSEvergreenClientBuilder.standard().withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(GREENGRASS_SERVICE_ENDPOINT, BETA_REGION.toString())).build();
    private static final PackageIdentifier[] testComponents = {
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
            createPackageIdentifier("NonDisruptableService", new Semver("1.0.1"))
    };

    @BeforeAll
    static void beforeAll() throws Exception {
        uploadTestComponentsToCms(true, testComponents);
    }

    @AfterAll
    static void afterAll() {
        for (PackageIdentifier component : testComponents) {
            // The delete API is not implemented on the service side yet. Currently API always returns 200
            DeleteComponentResult result = GreengrassPackageServiceHelper.deleteComponent(cmsClient,
                    component.getName(), component.getVersion().toString());
            assertEquals(200, result.getSdkHttpMetadata().getHttpStatusCode());
        }
    }

    protected BaseE2ETestCase() {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        thingGroupResp = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        thingGroupName = thingGroupResp.thingGroupName();
        createdThingGroups.add(thingGroupName);
    }

    protected void initKernel() throws IOException, DeviceConfigurationException {
        kernel = new Kernel().parseArgs("-r", tempRootDir.toAbsolutePath().toString());
        deviceProvisioningHelper.updateKernelConfigWithIotConfiguration(kernel, thingInfo, BETA_REGION.toString());
    }

    /**
     * Load recipes and artifacts from local store and publish components to CMS.
     * Directory tree layout should follow the local component store. e.g.
     * src/integrationtests/resources/com/aws/iot/evergreen/integrationtests/e2e
     * └── local_store_content
     *     ├── artifacts
     *     │   └── KernelIntegTest
     *     │       └── 1.0.0
     *     │           └── kernel_integ_test_artifact.txt
     *     └── recipes
     *         ├── KernelIntegTest-1.0.0.yaml
     *         └── KernelIntegTestDependency-1.0.0.yaml
     *
     * @param commit whether to call commitComponent with the created components
     * @param pkgIds list of component identifiers
     */
    private static void uploadTestComponentsToCms(boolean commit, PackageIdentifier... pkgIds)
            throws IOException, PackagingException {
        Path localStoreContentPath = Paths.get(BaseE2ETestCase.class.getResource("local_store_content").getPath());

        e2eTestPkgStoreDir = tempRootDir.resolve("eteTestPkgStore");
        // copy to tmp directory
        FileUtils.copyDirectory(localStoreContentPath.toFile(), e2eTestPkgStoreDir.toFile());

        e2eTestPackageStore = new PackageStore(e2eTestPkgStoreDir);

        List<String> errors = new ArrayList<>();
        for (PackageIdentifier pkgId : pkgIds) {
            try {
                draftComponent(pkgId);
            } catch (ResourceAlreadyExistException e) {
                // Don't fail the test if the component exists
                errors.add(e.getMessage());
            }

            if (commit) {
                try {
                    GreengrassPackageServiceHelper.commitComponent(cmsClient, pkgId.getName(),
                            pkgId.getVersion().toString());
                } catch (InvalidInputException e) {
                    // Don't fail the test if the component is already committed
                    errors.add(e.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            staticlogger.atWarn().kv("errors", errors).log("Ignore errors if a component already exists");
        }
    }

    private static void draftComponent(PackageIdentifier pkgIdCloud) throws IOException {

        PackageIdentifier pkgIdLocal = new PackageIdentifier(removeTestComponentNameCloudSuffix(pkgIdCloud.getName()),
                pkgIdCloud.getVersion(), pkgIdCloud.getScope());

        Path testRecipePath = e2eTestPackageStore.resolveRecipePath(pkgIdLocal);

        // update recipe
        String content = new String(Files.readAllBytes(testRecipePath), StandardCharsets.UTF_8);
        Set<String> componentNameSet = Arrays.stream(testComponents)
                .map(component -> component.getName()).collect(Collectors.toSet());

        for (String cloudPkgName: componentNameSet) {
            String localPkgName = removeTestComponentNameCloudSuffix(cloudPkgName);
            content = content.replaceAll("\\{\\{" + localPkgName + "}}", cloudPkgName);
        }

        testRecipePath = e2eTestPackageStore.resolveRecipePath(pkgIdCloud);

        Files.write(testRecipePath, content.getBytes(StandardCharsets.UTF_8));

        CreateComponentResult createComponentResult = GreengrassPackageServiceHelper.createComponent(cmsClient,
                testRecipePath);
        assertEquals("DRAFT", createComponentResult.getStatus());
        assertEquals(pkgIdCloud.getName(), createComponentResult.getComponentName(), createComponentResult.toString());
        assertEquals(pkgIdCloud.getVersion().toString(), createComponentResult.getComponentVersion());

        Path artifactDirPath = e2eTestPackageStore.resolveArtifactDirectoryPath(pkgIdLocal);
        File[] artifactFiles = artifactDirPath.toFile().listFiles();
        if (artifactFiles == null) {
            staticlogger.atInfo().kv("component", pkgIdLocal).kv("artifactPath", artifactDirPath.toAbsolutePath())
                    .log("Skip artifact upload. No artifacts found");
        } else {
            for (File artifact : artifactFiles) {
                GreengrassPackageServiceHelper.createAndUploadComponentArtifact(
                        cmsClient, artifact, pkgIdCloud.getName(), pkgIdCloud.getVersion().toString());
            }
        }
    }

    protected static synchronized AWSEvergreen getFcsClient() {
        if (fcsClient == null) {
            AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(
                    FCS_BETA_ENDPOINT, BETA_REGION.toString());
            fcsClient = AWSEvergreenClientBuilder.standard()
                    .withEndpointConfiguration(endpointConfiguration).build();
        }
        return fcsClient;
    }

    @SuppressWarnings("PMD.LinguisticNaming")
    protected PublishConfigurationResult setAndPublishFleetConfiguration(SetConfigurationRequest setRequest) {
        AWSEvergreen client = getFcsClient();
        Map<String, PackageMetaData> updatedPkgMetadata = new HashMap<>();
        setRequest.getPackages().forEach((key, val) -> {
            updatedPkgMetadata.put(getTestComponentNameInCloud(key), val);
        });
        setRequest.setPackages(updatedPkgMetadata);

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

    @Override
    public void close() throws Exception {
        if (fcsClient != null) {
            fcsClient.shutdown();
        }
        cmsClient.shutdown();
        iotClient.close();
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
