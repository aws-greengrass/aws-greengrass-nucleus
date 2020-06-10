/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.amazonaws.services.greengrassfleetconfiguration.model.FailureHandlingPolicy;
import com.amazonaws.services.greengrassfleetconfiguration.model.PackageMetaData;
import com.amazonaws.services.greengrassfleetconfiguration.model.PublishConfigurationResult;
import com.amazonaws.services.greengrassfleetconfiguration.model.SetConfigurationRequest;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessageSubstring;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class DeploymentE2ETest extends BaseE2ETestCase {

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
        // Cleanup all IoT thing resources we created
        cleanup();
    }

    @BeforeEach
    void launchKernel() throws Exception {
        initKernel();
        kernel.launch();

        // TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        Thread.sleep(10_000);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_removes_packages_THEN_services_should_be_stopped_and_job_is_successful() throws Exception {
        // First Deployment to have some services running in Kernel which can be removed later
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Second deployment to remove some services deployed previously
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("SomeService").getState());
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deployment_has_conflicts_THEN_job_should_fail_and_return_error(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessageSubstring(context, "Conflicts in resolving package: Mosquitto");

        // New deployment contains dependency conflicts
        SetConfigurationRequest setRequest = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("SomeOldService", new PackageMetaData().withRootComponent(true).withVersion("0.9.0"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult = setAndPublishFleetConfiguration(setRequest);

        String jobId = publishResult.getJobId();
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.FAILED));

        // Make sure IoT Job was marked as failed and provided correct reason
        String deploymentError = iotClient.describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId)
                .thingName(thingInfo.getThingName()).build()).execution().statusDetails().detailsMap().get("error");
        assertThat(deploymentError, StringContains.containsString(
                "com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException: Conflicts in resolving package: Mosquitto. Version constraints from upstream packages:"));
        assertThat(deploymentError, StringContains.containsString("SomeService-v1.0.0=1.0.0"));
        assertThat(deploymentError, StringContains.containsString("SomeOldService-v0.9.0==0.9.0"));
    }

    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_deploy_fix_THEN_service_run_and_job_is_successful(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context, "Service CustomerApp in broken state after deployment");

        // Create first Job Doc with a faulty service (CustomerApp-0.9.0)
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        // Wait for deployment job to fail after three retries of starting CustomerApp
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(7), s -> s.equals(JobExecutionStatus.FAILED));
        // CustomerApp should be in BROKEN state
        assertEquals(State.BROKEN, kernel.locate("CustomerApp").getState());

        // Create another job with a fix to the faulty service (CustomerApp-0.9.1).
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        // Ensure that main is FINISHED and CustomerApp is RUNNING.
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertEquals(State.RUNNING, kernel.locate("CustomerApp").getState());
    }

    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_failure_policy_is_rollback_THEN_deployment_is_rolled_back_and_job_fails(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context, "Service CustomerApp in broken state after deployment");

        // Deploy some services that can be used for verification later
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("RedSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                .addPackagesEntry("YellowSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Create a Job Doc with a faulty service (CustomerApp-0.9.0) requesting rollback on failure
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.ROLLBACK)
                .addPackagesEntry("RedSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                .addPackagesEntry("YellowSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        String jobId2 = publishResult2.getJobId();
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, jobId2, thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.FAILED));

        // Main should be INSTALLED state and CustomerApp should be stopped and removed
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("RedSignal")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("YellowSignal")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> kernel.locate("CustomerApp").getState());
        assertThrows(ServiceLoadException.class, () -> kernel.locate("Mosquitto").getState());
        assertThrows(ServiceLoadException.class, () -> kernel.locate("GreenSignal").getState());

        // IoT Job should have failed with correct message.
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE.name(), iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thingInfo.getThingName())
                        .build()).execution().statusDetails().detailsMap().get("detailed-deployment-status"));
    }
}
