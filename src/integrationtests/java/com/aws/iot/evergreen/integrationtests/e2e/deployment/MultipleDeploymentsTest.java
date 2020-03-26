/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentService.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.deployment.DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("E2E")
public class MultipleDeploymentsTest {
    @TempDir
    static Path tempRootDir;

    private static Kernel kernel;
    private static Utils.ThingInfo thing;
    private static final Logger logger = LogManager.getLogger(MultipleDeploymentsTest.class);

    @BeforeAll
    static void beforeAll() {
        System.setProperty("root", tempRootDir.toAbsolutePath().toString());
    }

    @BeforeEach
    void beforeEach() throws IOException {
        kernel = new Kernel().parseArgs("-i", MultipleDeploymentsTest.class.getResource("blank_config.yaml").toString());
        thing = Utils.setupIotResourcesAndInjectIntoKernel(kernel, tempRootDir);
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @AfterAll
    static void afterAll() {
        // Cleanup all IoT thing resources we created
        Utils.cleanAllCreatedThings();
        Utils.cleanAllCreatedJobs();
    }

    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_online_device_WHEN_create_multiple_deployments_THEN_deployments_execute_successfully_in_order()
            throws Exception {
        List<DeploymentJobHelper> helpers = Arrays.asList(
                new DeploymentJobHelper("GreenSignal"),
                new DeploymentJobHelper("SomeService"),
                new DeploymentJobHelper("CustomerApp"));

        kernel.launch();

        subscribeToLocalDeploymentStatus(kernel, helpers);

        // Create multiple jobs
        // TODO: Eventually switch this to target using Thing Group instead of individual Thing
        String[] targets = {thing.thingArn};
        for (DeploymentJobHelper helper : helpers) {
            helper.createJob(targets);
            Utils.waitForJobToReachExecutionStatus(helper.jobId, thing.thingName, Duration.ofMinutes(2),
                    JobExecutionStatus.QUEUED);
        }

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            assertTrue(helper.jobCompleted.await(5, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);
            Utils.waitForJobToComplete(helper.jobId, Duration.ofMinutes(2));

            assertEquals(State.FINISHED, kernel.locate(helper.targetPkgName).getState());
            assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                    DescribeJobExecutionRequest.builder().jobId(helper.jobId).thingName(thing.thingName).build())
                    .execution().status());
        }
    }

    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_offline_device_WHEN_create_multiple_deployments_THEN_deployments_execute_successfully_in_order_eventually()
            throws Exception {
        List<DeploymentJobHelper> helpers = Arrays.asList(
                new DeploymentJobHelper("GreenSignal"),
                new DeploymentJobHelper("SomeService"),
                new DeploymentJobHelper("CustomerApp"));

        // Create multiple jobs
        // TODO: Eventually switch this to target using Thing Group instead of individual Thing
        String[] targets = {thing.thingArn};
        for (DeploymentJobHelper helper : helpers) {
            helper.createJob(targets);
            Utils.waitForJobToReachExecutionStatus(helper.jobId, thing.thingName, Duration.ofMinutes(2),
                    JobExecutionStatus.QUEUED);
        }

        subscribeToLocalDeploymentStatus(kernel, helpers);

        // Start kernel and connect IoT cloud
        kernel.launch();

        // Wait for all jobs to finish
        for (DeploymentJobHelper helper : helpers) {
            assertTrue(helper.jobCompleted.await(5, TimeUnit.MINUTES), "Deployment job timed out: " + helper.jobId);
            Utils.waitForJobToComplete(helper.jobId, Duration.ofMinutes(2));

            assertEquals(State.FINISHED, kernel.locate(helper.targetPkgName).getState());
            assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient.describeJobExecution(
                    DescribeJobExecutionRequest.builder().jobId(helper.jobId).thingName(thing.thingName).build())
                    .execution().status());
        }
    }

    private void subscribeToLocalDeploymentStatus(Kernel kernel, List<DeploymentJobHelper> helpers) {
        Topics deploymentServiceTopics = kernel.lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS);
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
                    logger.atWarn().kv("jobId", helpers.get(i-1).jobId).log("Waiting for deployment job to complete");
                    break;
                }
                if (helpers.get(i).jobId.equals(jobId) && status.equals("SUCCEEDED")) {
                    logger.atWarn().kv("jobId", helpers.get(i).jobId).log("Deployment job has completed");
                    helpers.get(i).jobCompleted.countDown();
                    break;
                }
            }
        });
    }

    class DeploymentJobHelper {
        String jobId;
        CountDownLatch jobCompleted;
        String targetPkgName;

        public DeploymentJobHelper(String pkgName) {
            jobId = UUID.randomUUID().toString();
            jobCompleted= new CountDownLatch(1);
            targetPkgName = pkgName;
        }

        public void createJob(String[] targets) throws JsonProcessingException {
            String document = new ObjectMapper().writeValueAsString(
                    DeploymentDocument.builder().timestamp(System.currentTimeMillis())
                            .deploymentId(UUID.randomUUID().toString()).rootPackages(Arrays.asList(targetPkgName))
                            .deploymentPackageConfigurationList(Arrays.asList(
                                    new DeploymentPackageConfiguration(targetPkgName, "1.0.0", null, null, null))).build());
            Utils.createJobWithId(document, targets, jobId);
            logger.atWarn().kv("jobId", jobId).log("Created IoT Job");
        }
    }
}
