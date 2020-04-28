/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.integrationtests.e2e.util.DeploymentJobHelper;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentService.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class MultipleDeploymentsTest {
    @TempDir
    Path tempRootDir;

    private Kernel kernel;
    private Utils.ThingInfo thing;
    private CreateThingGroupResponse thingGroupResp;
    private final Set<String> createdIotJobs = new HashSet<>();

    private static final Logger logger = LogManager.getLogger(MultipleDeploymentsTest.class);

    @BeforeEach
    void beforeEach() throws IOException {
        kernel = new Kernel().parseArgs("-i", MultipleDeploymentsTest.class.getResource("blank_config.yaml")
                .toString(), "-r", tempRootDir.toAbsolutePath().toString());
        thing = Utils.createThing();
        Utils.updateKernelConfigWithIotConfiguration(kernel, thing);
        thingGroupResp = Utils.createThingGroupAndAddThing(thing);

        Path localStoreContentPath = Paths
                .get(MultipleDeploymentsTest.class.getResource("local_store_content").getPath());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }

        // Cleanup all IoT thing resources we created
        Utils.cleanThing(thing);
        createdIotJobs.forEach(jobId -> Utils.cleanJob(jobId));
        createdIotJobs.clear();
        Utils.cleanThingGroup(thingGroupResp.thingGroupName());
    }

    // In this test, we bring the device online and connected to IoT Core, and then create 3 deployments in a row.
    // The device would receive job notifications once for each deployment job in most cases. We are able to verify
    // deployment service can process the deployments one by one based on the job order from IoT jobs.
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_online_device_WHEN_create_multiple_deployments_THEN_deployments_execute_successfully_in_order() throws Exception {
        List<DeploymentJobHelper> helpers = Arrays
                .asList(new DeploymentJobHelper("GreenSignal"), new DeploymentJobHelper("SomeService"), new DeploymentJobHelper("CustomerApp"));

        kernel.launch();

        subscribeToLocalDeploymentStatus(kernel, helpers);

        // Create multiple jobs
        String[] targets = {thingGroupResp.thingGroupArn()};
        for (DeploymentJobHelper helper : helpers) {
            Utils.createJobWithId(helper.createIoTJobDocument(), helper.jobId, targets);
            createdIotJobs.add(helper.jobId);
            Utils.waitForJobExecutionStatusToSatisfy(helper.jobId, thing.thingName, Duration.ofMinutes(1), s -> s
                    .ordinal() >= JobExecutionStatus.QUEUED.ordinal());
            logger.atWarn().kv("jobId", helper.jobId).log("Created IoT Job");
        }

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            assertTrue(helper.jobCompleted.await(2, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);
            Utils.waitForJobToComplete(helper.jobId, Duration.ofMinutes(5));

            assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient
                    .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(helper.jobId)
                            .thingName(thing.thingName).build()).execution().status());
        }
    }

    // In this test, we create 3 deployments in a row, and then bring the device online and connected to IoT Core.
    // The device would receive job notifications at least 3 times for the first deployment job. This is expected
    // behavior from IoT jobs. Thus we are able to verify deployment service can handle the duplicate job
    // notifications in this scenario.
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_offline_device_WHEN_create_multiple_deployments_THEN_deployments_execute_successfully_in_order_eventually() throws Exception {
        List<DeploymentJobHelper> helpers = Arrays
                .asList(new DeploymentJobHelper("GreenSignal"), new DeploymentJobHelper("SomeService"), new DeploymentJobHelper("CustomerApp"));

        // Create multiple jobs
        String[] targets = {thingGroupResp.thingGroupArn()};
        for (DeploymentJobHelper helper : helpers) {
            Utils.createJobWithId(helper.createIoTJobDocument(), helper.jobId, targets);
            createdIotJobs.add(helper.jobId);
            Utils.waitForJobExecutionStatusToSatisfy(helper.jobId, thing.thingName, Duration.ofMinutes(1), s -> s
                    .ordinal() >= JobExecutionStatus.QUEUED.ordinal());
            logger.atWarn().kv("jobId", helper.jobId).log("Created IoT Job");
        }

        subscribeToLocalDeploymentStatus(kernel, helpers);

        // Start kernel and connect IoT cloud
        kernel.launch();

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            assertTrue(helper.jobCompleted.await(2, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);
            Utils.waitForJobToComplete(helper.jobId, Duration.ofMinutes(5));

            assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient
                    .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(helper.jobId)
                            .thingName(thing.thingName).build()).execution().status());
        }
    }

    private void subscribeToLocalDeploymentStatus(Kernel kernel, List<DeploymentJobHelper> helpers) {
        Topics deploymentServiceTopics = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS);
        Topics processedDeployments = deploymentServiceTopics.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
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
