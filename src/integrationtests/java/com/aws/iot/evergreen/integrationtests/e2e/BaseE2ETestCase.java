/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.greengrasscomponentmanagement.model.CreateComponentResult;
import com.amazonaws.services.greengrasscomponentmanagement.model.InvalidInputException;
import com.amazonaws.services.greengrasscomponentmanagement.model.ResourceAlreadyExistException;
import com.amazonaws.services.greengrassfleetconfiguration.AWSGreengrassFleetConfiguration;
import com.amazonaws.services.greengrassfleetconfiguration.AWSGreengrassFleetConfigurationClientBuilder;
import com.amazonaws.services.greengrassfleetconfiguration.model.PublishConfigurationRequest;
import com.amazonaws.services.greengrassfleetconfiguration.model.PublishConfigurationResult;
import com.amazonaws.services.greengrassfleetconfiguration.model.SetConfigurationRequest;
import com.amazonaws.services.greengrassfleetconfiguration.model.SetConfigurationResult;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

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

    protected final Set<String> createdIotJobIds = new HashSet<>();
    protected DeviceProvisioningHelper.ThingInfo thingInfo;
    protected String thingGroupName;
    protected CreateThingGroupResponse thingGroupResp;
    protected DeviceProvisioningHelper deviceProvisioningHelper =
            new DeviceProvisioningHelper(BETA_REGION.toString(), System.out);

    @TempDir
    protected Path tempRootDir;

    protected Kernel kernel;

    protected static final IotClient iotClient = IotSdkClientFactory.getIotClient(BETA_REGION.toString());
    private static AWSGreengrassFleetConfiguration fcsClient;

    protected BaseE2ETestCase() {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        thingGroupResp = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        thingGroupName = thingGroupResp.thingGroupName();
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
    protected void uploadTestComponentsToCms(boolean commit, PackageIdentifier... pkgIds)
            throws IOException, PackagingException {
        GreengrassPackageServiceHelper cmsHelper = kernel.getContext().get(GreengrassPackageServiceHelper.class);

        // Avoid uploading the same components at the same time, since E2E tests share some test components
        synchronized (BaseE2ETestCase.class) {
            for (PackageIdentifier pkgId : pkgIds) {
                try {
                    draftComponent(cmsHelper, pkgId);
                } catch (ResourceAlreadyExistException e) {
                    // Don't fail the test if the component exists
                    logger.atWarn().kv("component", pkgId).log(e.getMessage());
                }

                if (commit) {
                    try {
                        cmsHelper.commitComponent(pkgId.getName(), pkgId.getVersion().toString());
                    } catch (InvalidInputException e) {
                        // Don't fail the test if the component is already committed
                        logger.atWarn().kv("component", pkgId).log(e.getMessage());
                    }
                }
            }
        }
    }

    private void draftComponent(GreengrassPackageServiceHelper cmsHelper, PackageIdentifier pkgId)
            throws PackagingException, IOException {
        Path localStoreContentPath = Paths.get(BaseE2ETestCase.class.getResource("local_store_content").getPath());
        PackageStore e2eTestPackageStore = new PackageStore(localStoreContentPath);

        Path testRecipePath = e2eTestPackageStore.resolveRecipePath(pkgId);
        CreateComponentResult createComponentResult = cmsHelper.createComponent(testRecipePath);
        assertEquals("DRAFT", createComponentResult.getStatus());
        assertEquals(pkgId.getName(), createComponentResult.getComponentName());
        assertEquals(pkgId.getVersion().toString(), createComponentResult.getComponentVersion());

        Path artifactDirPath = e2eTestPackageStore.resolveArtifactDirectoryPath(pkgId);
        File[] artifactFiles = artifactDirPath.toFile().listFiles();
        if (artifactFiles == null) {
            logger.atInfo().kv("component", pkgId).kv("artifactPath", artifactDirPath.toAbsolutePath())
                    .log("Skip artifact upload. No artifacts found");
        } else {
            for (File artifact : artifactFiles) {
                cmsHelper.uploadComponentArtifact(artifact, pkgId.getName(), pkgId.getVersion().toString());
            }
        }
    }

    protected static synchronized AWSGreengrassFleetConfiguration getFcsClient() {
        if (fcsClient == null) {
            AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(
                    FCS_BETA_ENDPOINT, BETA_REGION.toString());
            fcsClient = AWSGreengrassFleetConfigurationClientBuilder.standard()
                    .withEndpointConfiguration(endpointConfiguration).build();
        }
        return fcsClient;
    }

    @SuppressWarnings("PMD.LinguisticNaming")
    protected PublishConfigurationResult setAndPublishFleetConfiguration(SetConfigurationRequest setRequest) {
        AWSGreengrassFleetConfiguration client = getFcsClient();
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
        IotJobsUtils.cleanThingGroup(iotClient, thingGroupName);
        createdIotJobIds.forEach(jobId -> IotJobsUtils.cleanJob(iotClient, jobId));
        createdIotJobIds.clear();
    }

    @Override
    public void close() throws Exception {
        if (fcsClient != null) {
            fcsClient.shutdown();
        }
        iotClient.close();
    }
}
