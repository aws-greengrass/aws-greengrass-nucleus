/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.services.greengrassv2.model.ComponentDeploymentSpecification;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUNTIME_STORE_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessageSubstring;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
@Tag("E2E")
class MultipleDeploymentsTest extends BaseE2ETestCase {

    protected MultipleDeploymentsTest() throws Exception {
        super();
    }

    @BeforeEach
    void beforeEach2(ExtensionContext context) throws Exception {
        // Depending on the order that we receive the jobs, we expect that the job may already be canceled, so this
        // exception is fine and we want the test to continue.
        ignoreExceptionUltimateCauseWithMessageSubstring(context, "finished with status CANCELED");
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
    void GIVEN_offline_device_WHEN_create_multiple_deployments_for_same_group_THEN_last_deployment_execute_successfully()
            throws Exception {
        DeploymentJobHelper mostRecentJobHelper = new DeploymentJobHelper(3, "CustomerApp");
        List<DeploymentJobHelper> helpers =
                Arrays.asList(new DeploymentJobHelper(1, "GreenSignal"), new DeploymentJobHelper(2, "SomeService"),
                        mostRecentJobHelper);

        // Create multiple jobs
        for (DeploymentJobHelper helper : helpers) {
            CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().components(
                    Utils.immutableMap(helper.targetPkgName,
                            ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();

            CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest);
            helper.jobId = createDeploymentResult.iotJobId();

            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, helper.jobId, thingInfo.getThingName(),
                    Duration.ofMinutes(1), s -> s.ordinal() >= JobExecutionStatus.QUEUED.ordinal());
            logger.atWarn().kv("jobId", helper.jobId).kv("index", helper.index).log("Created IoT Job");
        }
        // Only the last job should execute
        subscribeToLocalDeploymentStatus(kernel, Arrays.asList(mostRecentJobHelper));

        // Start Nucleus and connect IoT cloud
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
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS, RUNTIME_STORE_NAMESPACE_TOPIC,
                        PROCESSED_DEPLOYMENTS_TOPICS);
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
                if (helpers.get(i).jobId.equals(jobId) && "SUCCEEDED" .equals(status)) {
                    logger.atWarn().kv("jobId", helpers.get(i).jobId).log("Deployment job has completed");
                    helpers.get(i).jobCompleted.countDown();
                    break;
                }
            }
        });
    }

    class DeploymentJobHelper {
        public int index;
        public String jobId;
        public CountDownLatch jobCompleted;
        public String targetPkgName;

        DeploymentJobHelper(int index, String pkgName) {
            this.index = index;
            jobId = UUID.randomUUID().toString();
            jobCompleted = new CountDownLatch(1);
            targetPkgName = pkgName;
        }
    }
}
