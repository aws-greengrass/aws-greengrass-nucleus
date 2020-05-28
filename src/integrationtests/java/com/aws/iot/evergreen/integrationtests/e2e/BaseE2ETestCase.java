/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e;

import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.greengrassfleetconfiguration.AWSGreengrassFleetConfiguration;
import com.amazonaws.services.greengrassfleetconfiguration.AWSGreengrassFleetConfigurationClientBuilder;
import com.amazonaws.services.greengrassfleetconfiguration.model.PublishConfigurationRequest;
import com.amazonaws.services.greengrassfleetconfiguration.model.PublishConfigurationResult;
import com.amazonaws.services.greengrassfleetconfiguration.model.SetConfigurationRequest;
import com.amazonaws.services.greengrassfleetconfiguration.model.SetConfigurationResult;
import com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

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
    protected DeviceProvisioningHelper deviceProvisioningHelper = new DeviceProvisioningHelper(BETA_REGION.toString());

    @TempDir
    protected Path tempRootDir;

    protected static final IotClient iotClient = IotClient.builder().region(BETA_REGION).build();
    private static AWSGreengrassFleetConfiguration fcsClient;
    // TODO: add CMS client

    protected BaseE2ETestCase() {
        thingInfo = deviceProvisioningHelper.createThingForE2ETests();
        thingGroupResp = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        thingGroupName = thingGroupResp.thingGroupName();
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
