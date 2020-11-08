/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.ComponentInfo;
import com.amazonaws.services.evergreen.model.CreateDeploymentRequest;
import com.amazonaws.services.evergreen.model.CreateDeploymentResult;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.DeploymentJobHelper;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
@Tag("E2E")
class MultipleDeploymentsTest extends BaseE2ETestCase {

    protected MultipleDeploymentsTest() throws Exception {
        super();
    }

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

    // In this test, we create 3 deployments in a row, and then bring the device online and connected to IoT Core.
    // The device would receive job notifications at least 3 times for the first deployment job. This is expected
    // behavior from IoT jobs. Thus we are able to verify deployment service can handle the duplicate job
    // notifications in this scenario.
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_offline_device_WHEN_create_multiple_deployments_for_same_group_THEN_last_deployment_execute_successfully() throws Exception {
        DeploymentJobHelper mostRecentJobHelper = new DeploymentJobHelper(3, "CustomerApp");
        List<DeploymentJobHelper> helpers = Arrays
                .asList(new DeploymentJobHelper(1, "GreenSignal"), new DeploymentJobHelper(2, "SomeService"),
                        mostRecentJobHelper);

        // Create multiple jobs
        for (DeploymentJobHelper helper : helpers) {
            CreateDeploymentRequest createDeploymentRequest = new CreateDeploymentRequest()
                    .addComponentsEntry(helper.targetPkgName, new ComponentInfo().withVersion("1.0.0"));

            CreateDeploymentResult createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest);
            helper.jobId = createDeploymentResult.getJobId();

            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                    Duration.ofMinutes(1), s -> s.ordinal() >= JobExecutionStatus.QUEUED.ordinal());
            logger.atWarn().kv("jobId", helper.jobId).kv("index", helper.index).log("Created IoT Job");
        }
        // Only the last job should execute
        subscribeToLocalDeploymentStatus(kernel, Arrays.asList(mostRecentJobHelper));

        // Start kernel and connect IoT cloud
        kernel.launch();

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            if (helper.index == 3) {
                assertTrue(helper.jobCompleted.await(2, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);

                IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                        Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
            } else {
                IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                        Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.CANCELED));
            }
        }
    }

    private void subscribeToLocalDeploymentStatus(Kernel kernel, List<DeploymentJobHelper> helpers) {
        Topics processedDeployments = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                        RUNTIME_STORE_NAMESPACE_TOPIC, PROCESSED_DEPLOYMENTS_TOPICS);
        processedDeployments.subscribe((whatHappened, newValue) -> {
            if (!(newValue instanceof Topics) || whatHappened == WhatHappened.interiorAdded) {
                return;
            }
            Map<String, Object> deploymentDetails = ((Topics) newValue).toPOJO();
            String jobId = deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME).toString();
            String status = deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME).toString();

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
