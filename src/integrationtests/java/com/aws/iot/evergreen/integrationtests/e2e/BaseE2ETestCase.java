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
import com.amazonaws.services.evergreen.model.PublishConfigurationRequest;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.ResourceAlreadyExistException;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.amazonaws.services.evergreen.model.SetConfigurationResult;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceHelper;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.util.IotSdkClientFactory;
import com.vdurmont.semver4j.Semver;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

    protected final Set<String> createdIotJobIds = new HashSet<>();
    protected final Set<String> createdThingGroups = new HashSet<>();
    protected DeviceProvisioningHelper.ThingInfo thingInfo;
    protected String thingGroupName;
    protected CreateThingGroupResponse thingGroupResp;
    protected DeviceProvisioningHelper deviceProvisioningHelper =
            new DeviceProvisioningHelper(BETA_REGION.toString(), System.out);

    @TempDir
    protected Path tempRootDir;

    protected Kernel kernel;

    protected static final IotClient iotClient = IotSdkClientFactory
            .getIotClient(BETA_REGION.toString(), Collections.singleton(InvalidRequestException.class));
    private static AWSEvergreen fcsClient;
    protected static final AWSEvergreen cmsClient =
            AWSEvergreenClientBuilder.standard().withEndpointConfiguration(
            new AwsClientBuilder.EndpointConfiguration(GREENGRASS_SERVICE_ENDPOINT, BETA_REGION.toString())).build();
    private static final PackageIdentifier[] testComponents = {
            new PackageIdentifier("CustomerApp", new Semver("1.0.0")),
            new PackageIdentifier("CustomerApp", new Semver("0.9.0")),
            new PackageIdentifier("CustomerApp", new Semver("0.9.1")),
            new PackageIdentifier("SomeService", new Semver("1.0.0")),
            new PackageIdentifier("SomeOldService", new Semver("0.9.0")),
            new PackageIdentifier("GreenSignal", new Semver("1.0.0")),
            new PackageIdentifier("RedSignal", new Semver("1.0.0")),
            new PackageIdentifier("YellowSignal", new Semver("1.0.0")),
            new PackageIdentifier("Mosquitto", new Semver("1.0.0")),
            new PackageIdentifier("Mosquitto", new Semver("0.9.0")),
            new PackageIdentifier("KernelIntegTest", new Semver("1.0.0")),
            new PackageIdentifier("KernelIntegTestDependency", new Semver("1.0.0")),
            new PackageIdentifier("Log", new Semver("2.0.0")),
            new PackageIdentifier("NonDisruptableService", new Semver("1.0.0")),
            new PackageIdentifier("NonDisruptableService", new Semver("1.0.1"))
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

    protected void initKernel() throws IOException {
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

    private static void draftComponent(PackageIdentifier pkgId) throws PackagingException, IOException {
        Path localStoreContentPath = Paths.get(BaseE2ETestCase.class.getResource("local_store_content").getPath());
        PackageStore e2eTestPackageStore = new PackageStore(localStoreContentPath);

        Path testRecipePath = e2eTestPackageStore.resolveRecipePath(pkgId);
        CreateComponentResult createComponentResult = GreengrassPackageServiceHelper.createComponent(cmsClient,
                testRecipePath);
        assertEquals("DRAFT", createComponentResult.getStatus());
        assertEquals(pkgId.getName(), createComponentResult.getComponentName());
        assertEquals(pkgId.getVersion().toString(), createComponentResult.getComponentVersion());

        Path artifactDirPath = e2eTestPackageStore.resolveArtifactDirectoryPath(pkgId);
        File[] artifactFiles = artifactDirPath.toFile().listFiles();
        if (artifactFiles == null) {
            staticlogger.atInfo().kv("component", pkgId).kv("artifactPath", artifactDirPath.toAbsolutePath())
                    .log("Skip artifact upload. No artifacts found");
        } else {
            for (File artifact : artifactFiles) {
                GreengrassPackageServiceHelper.createAndUploadComponentArtifact(cmsClient, artifact, pkgId.getName(),
                        pkgId.getVersion().toString());
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
    }

    @Override
    public void close() throws Exception {
        if (fcsClient != null) {
            fcsClient.shutdown();
        }
        cmsClient.shutdown();
        iotClient.close();
    }
}
