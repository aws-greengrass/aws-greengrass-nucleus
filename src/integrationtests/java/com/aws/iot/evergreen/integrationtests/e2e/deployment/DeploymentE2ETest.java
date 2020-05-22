/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.FailureHandlingPolicy;
import com.aws.iot.evergreen.deployment.model.FleetConfiguration;
import com.aws.iot.evergreen.deployment.model.PackageInfo;
import com.aws.iot.evergreen.integrationtests.e2e.util.FileUtils;
import com.aws.iot.evergreen.integrationtests.e2e.util.Utils;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.DescribeJobRequest;
import software.amazon.awssdk.services.iot.model.JobExecution;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;
import software.amazon.awssdk.services.iot.model.JobStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.integrationtests.e2e.util.Utils.generateMockConfigurationArn;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessageSubstring;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class DeploymentE2ETest {
    @TempDir
    Path tempRootDir;

    private Kernel kernel;
    private Utils.ThingInfo thing;
    private CreateThingGroupResponse thingGroupResp;
    private final Set<String> createdIotJobs = new HashSet<>();

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

    private void launchKernel(String configFile) throws IOException, InterruptedException {
        kernel = new Kernel()
                .parseArgs("-i", DeploymentE2ETest.class.getResource(configFile).toString(), "-r", tempRootDir
                        .toAbsolutePath().toString());
        thing = Utils.createThing();
        Utils.updateKernelConfigWithIotConfiguration(kernel, thing);
        kernel.launch();
        thingGroupResp = Utils.createThingGroupAndAddThing(thing);

        Path localStoreContentPath = Paths.get(DeploymentE2ETest.class.getResource("local_store_content").getPath());
        // pre-load contents to package store
        FileUtils.copyFolderRecursively(localStoreContentPath, kernel.getPackageStorePath());

        // TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        Thread.sleep(10_000);
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deploy_new_services_e2e_THEN_new_services_deployed_and_job_is_successful() throws Exception {
        launchKernel("blank_config.yaml");

        // Create Job Doc
        String document = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("add/svc:1"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("CustomerApp", new PackageInfo(true, "1.0.0", null));
                        }}).build());

        // Create job targeting our DUT
        String[] targets = {thingGroupResp.thingGroupArn()};
        String jobId = Utils.createJob(document, targets);
        createdIotJobs.add(jobId);

        // Wait for the job to complete
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(5));
        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName)
                        .build()).execution().status());
        assertEquals(JobStatus.COMPLETED, Utils.iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build())
                .job().status());
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_removes_packages_THEN_services_should_be_stopped_and_job_is_successful() throws Exception {
        launchKernel("blank_config.yaml");

        // Target our DUT for deployments
        String[] targets = {thingGroupResp.thingGroupArn()};

        // First Deployment to have some services running in Kernel which can be removed later
        String document1 = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("remove/svc:1"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("CustomerApp", new PackageInfo(true, "1.0.0", null));
                            put("SomeService", new PackageInfo(true, "1.0.0", null));
                        }}).build());
        String jobId1 = Utils.createJob(document1, targets);
        createdIotJobs.add(jobId1);
        Utils.waitForJobToComplete(jobId1, Duration.ofMinutes(5));

        // Second deployment to remove some services deployed previously
        String document2 = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("remove/svc:2"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("CustomerApp", new PackageInfo(true, "1.0.0", null));
                        }}).build());
        String jobId2 = Utils.createJob(document2, targets);
        createdIotJobs.add(jobId2);
        Utils.waitForJobToComplete(jobId2, Duration.ofMinutes(5));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("SomeService").getState());

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thing.thingName)
                        .build()).execution().status());
        assertEquals(JobStatus.COMPLETED, Utils.iotClient
                .describeJob(DescribeJobRequest.builder().jobId(jobId2).build()).job().status());
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deployment_has_conflicts_THEN_job_should_fail_and_return_error(ExtensionContext context) throws Exception {
        launchKernel("blank_config.yaml");

        ignoreExceptionUltimateCauseWithMessageSubstring(context, "Conflicts in resolving package: Mosquitto");

        // Target our DUT for deployments
        String[] targets = {thingGroupResp.thingGroupArn()};

        // New deployment contains dependency conflicts
        String document = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("fail/conflict:1"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("SomeService", new PackageInfo(true, "1.0.0", null));
                            put("SomeOldService", new PackageInfo(true, "0.9.0", null));
                        }}).build());
        String jobId = Utils.createJob(document, targets);
        createdIotJobs.add(jobId);
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(5));

        // Make sure IoT Job was marked as failed and provided correct reason
        JobExecution jobExecution = Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName)
                        .build()).execution();
        assertEquals(JobExecutionStatus.FAILED, jobExecution.status());

        String deploymentError = jobExecution.statusDetails().detailsMap().get("error");
        assertThat(deploymentError, StringContains
                .containsString("com.aws.iot.evergreen.packagemanager.exceptions" + ".PackageVersionConflictException: Conflicts in resolving package: Mosquitto. Version constraints from upstream packages:"));
        assertThat(deploymentError, StringContains.containsString("SomeService-v1.0.0=1.0.0"));
        assertThat(deploymentError, StringContains.containsString("SomeOldService-v0.9.0==0.9.0"));
        assertEquals(JobStatus.COMPLETED, Utils.iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build())
                .job().status());
    }

    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_deploy_fix_THEN_service_run_and_job_is_successful(ExtensionContext context) throws Exception {
        launchKernel("blank_config.yaml");

        ignoreExceptionUltimateCauseWithMessage(context, "Service CustomerApp in broken state after deployment");

        // Create first Job Doc with a faulty service (CustomerApp-0.9.0)
        String document = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("fail/broken:1"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("CustomerApp", new PackageInfo(true, "0.9.0", null));
                        }}).build());

        // Create job targeting our DUT.
        String[] targets = {thingGroupResp.thingGroupArn()};
        String jobId = Utils.createJob(document, targets);
        createdIotJobs.add(jobId);

        // Wait for deployment job to complete after three retries of starting CustomerApp
        Utils.waitForJobToComplete(jobId, Duration.ofMinutes(7));
        // CustomerApp should be in BROKEN state
        assertEquals(State.BROKEN, kernel.locate("CustomerApp").getState());

        // IoT Job should have failed.
        assertEquals(JobExecutionStatus.FAILED, Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thing.thingName)
                        .build()).execution().status());
        assertEquals(JobStatus.COMPLETED, Utils.iotClient.describeJob(DescribeJobRequest.builder().jobId(jobId).build())
                .job().status());

        // Create another job with a fix to the faulty service (CustomerApp-0.9.1).
        String document2 = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("fail/broken:2"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("CustomerApp", new PackageInfo(true, "0.9.1", null));
                        }}).build());

        String jobId2 = Utils.createJob(document2, targets);
        createdIotJobs.add(jobId2);

        Utils.waitForJobToComplete(jobId2, Duration.ofMinutes(5));
        // Ensure that main is FINISHED and CustomerApp is RUNNING.
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertEquals(State.RUNNING, kernel.locate("CustomerApp").getState());

        // Make sure that IoT Job was marked as successful
        assertEquals(JobExecutionStatus.SUCCEEDED, Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thing.thingName)
                        .build()).execution().status());
        assertEquals(JobStatus.COMPLETED, Utils.iotClient
                .describeJob(DescribeJobRequest.builder().jobId(jobId2).build()).job().status());
    }

    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_failure_policy_is_rollback_THEN_deployment_is_rolled_back_and_job_fails(ExtensionContext context) throws Exception {
        launchKernel("blank_config.yaml");

        ignoreExceptionUltimateCauseWithMessage(context, "Service CustomerApp in broken state after deployment");

        String[] targets = {thingGroupResp.thingGroupArn()};

        // Deploy some services that can be used for verification later
        String document1 = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("fail/rollback:1"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("RedSignal", new PackageInfo(true, "1.0.0", null));
                            put("YellowSignal", new PackageInfo(true, "1.0.0", null));
                        }}).build());
        String jobId1 = Utils.createJob(document1, targets);
        createdIotJobs.add(jobId1);
        Utils.waitForJobToComplete(jobId1, Duration.ofMinutes(5));

        // Create a Job Doc with a faulty service (CustomerApp-0.9.0) requesting rollback on failure
        String document2 = new ObjectMapper()
                .writeValueAsString(FleetConfiguration.builder()
                        .configurationArn(generateMockConfigurationArn("fail/rollback:2"))
                        .creationTimestamp(System.currentTimeMillis())
                        .failureHandlingPolicy(FailureHandlingPolicy.ROLLBACK)
                        .packages(new HashMap<String, PackageInfo>() {{
                            put("RedSignal", new PackageInfo(true, "1.0.0", null));
                            put("YellowSignal", new PackageInfo(true, "1.0.0", null));
                            put("CustomerApp", new PackageInfo(true, "0.9.0", null));
                        }}).build());
        String jobId2 = Utils.createJob(document2, targets);
        createdIotJobs.add(jobId2);
        Utils.waitForJobToComplete(jobId2, Duration.ofMinutes(5));

        // Main should be INSTALLED state and CustomerApp should be stopped and removed
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("RedSignal")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("YellowSignal")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("CustomerApp").getState());
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto").getState());
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal").getState());

        // IoT Job should have failed.
        assertEquals(JobExecutionStatus.FAILED, Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thing.thingName)
                        .build()).execution().status());
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE.name(), Utils.iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thing.thingName)
                        .build()).execution().statusDetails().detailsMap().get("detailed-deployment-status"));
        assertEquals(JobStatus.COMPLETED, Utils.iotClient
                .describeJob(DescribeJobRequest.builder().jobId(jobId2).build()).job().status());
    }
}
