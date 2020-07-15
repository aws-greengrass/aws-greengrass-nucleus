/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.DeploymentJobHelper;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.iot.evergreen.kernel.EvergreenService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class MultipleDeploymentsTest extends BaseE2ETestCase {

    @BeforeEach
    void beforeEach() throws Exception {
        initKernel();
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }

        // Cleanup all IoT thing resources we created
        cleanup();
    }

    // In this test, we bring the device online and connected to IoT Core, and then create 3 deployments in a row.
    // The device would receive job notifications once for each deployment job in most cases. We are able to verify
    // deployment service can process the deployments one by one based on the job order from IoT jobs.
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_online_device_WHEN_create_multiple_deployments_THEN_deployments_execute_successfully_in_order() throws Exception {
        List<DeploymentJobHelper> helpers = Arrays
                .asList(new DeploymentJobHelper(1, "GreenSignal"), new DeploymentJobHelper(2, "SomeService"),
                        new DeploymentJobHelper(3, "CustomerApp"));

        kernel.launch();

        subscribeToLocalDeploymentStatus(kernel, helpers);

        // Create multiple jobs
        String[] targets = {thingGroupResp.thingGroupArn()};
        for (DeploymentJobHelper helper : helpers) {
            // Note: Directly creating IoT jobs here so that we have definitive job IDs to make assertions on job
            // execution.
            IotJobsUtils.createJobWithId(iotClient, helper.createIoTJobDocument(), helper.jobId, targets);
            createdIotJobIds.add(helper.jobId);
            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                    Duration.ofMinutes(1), s -> s.ordinal() >= JobExecutionStatus.QUEUED.ordinal());
            logger.atWarn().kv("jobId", helper.jobId).log("Created IoT Job");
        }

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            assertTrue(helper.jobCompleted.await(3, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);
            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                    Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        }
    }

    // In this test, we create 3 deployments in a row, and then bring the device online and connected to IoT Core.
    // The device would receive job notifications at least 3 times for the first deployment job. This is expected
    // behavior from IoT jobs. Thus we are able to verify deployment service can handle the duplicate job
    // notifications in this scenario.
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
//    @Test
    void GIVEN_offline_device_WHEN_create_multiple_deployments_THEN_deployments_execute_successfully_in_order_eventually() throws Exception {
        List<DeploymentJobHelper> helpers = Arrays
                .asList(new DeploymentJobHelper(1, "GreenSignal"), new DeploymentJobHelper(2, "SomeService"),
                        new DeploymentJobHelper(3, "CustomerApp"));

        // Create multiple jobs
        for (DeploymentJobHelper helper : helpers) {
            SetConfigurationRequest setRequest = new SetConfigurationRequest()
                    .withTargetName(thingGroupName)
                    .withTargetType(THING_GROUP_TARGET_TYPE)
                    .withFailureHandlingPolicy(com.amazonaws.services.evergreen.model.FailureHandlingPolicy.DO_NOTHING)
                    .addPackagesEntry(helper.targetPkgName, new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));

            PublishConfigurationResult publishResult = setAndPublishFleetConfiguration(setRequest);
            helper.jobId = publishResult.getJobId();

            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                    Duration.ofMinutes(1), s -> s.ordinal() >= JobExecutionStatus.QUEUED.ordinal());
            logger.atWarn().kv("jobId", helper.jobId).log("Created IoT Job");
        }

        subscribeToLocalDeploymentStatus(kernel, helpers);

        // Start kernel and connect IoT cloud
        kernel.launch();

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            assertTrue(helper.jobCompleted.await(2, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);

            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                    Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        }
    }

    private void subscribeToLocalDeploymentStatus(Kernel kernel, List<DeploymentJobHelper> helpers) {
        Topics processedDeployments = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                        RUNTIME_STORE_NAMESPACE_TOPIC, PROCESSED_DEPLOYMENTS_TOPICS);
        processedDeployments.subscribe((whatHappened, newValue) -> {
            if (!(newValue instanceof Topic)) {
                return;
            }
            Map<String, Object> deploymentDetails = (HashMap) ((Topic) newValue).getOnce();
            String jobId = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID).toString();
            String status = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS).toString();

            for (int i = 0; i < helpers.size(); i++) {
                if (i > 0 && helpers.get(i - 1).jobCompleted.getCount() > 0) {
                    logger.atWarn().kv("jobId", helpers.get(i - 1).jobId).log("Waiting for deployment job to complete");
                    break;
                }
                if (helpers.get(i).jobId.equals(jobId) && "SUCCEEDED".equals(status)) {
                    logger.atWarn().kv("jobId", helpers.get(i).jobId).log("Deployment job has completed");
                    helpers.get(i).jobCompleted.countDown();
                    break;
                }
            }
        });
    }
}
