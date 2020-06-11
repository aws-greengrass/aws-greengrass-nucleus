/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.deployment;

import com.amazonaws.services.greengrassfleetconfiguration.model.FailureHandlingPolicy;
import com.amazonaws.services.greengrassfleetconfiguration.model.PackageMetaData;
import com.amazonaws.services.greengrassfleetconfiguration.model.PublishConfigurationResult;
import com.amazonaws.services.greengrassfleetconfiguration.model.SetConfigurationRequest;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.integrationtests.e2e.BaseE2ETestCase;
import com.aws.iot.evergreen.integrationtests.e2e.util.IotJobsUtils;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(EGExtension.class)
@Tag("E2E")
class MultipleGroupsDeploymentE2ETest extends BaseE2ETestCase {

    private final CreateThingGroupResponse secondThingGroupResponse;

    public MultipleGroupsDeploymentE2ETest() {
        super();
        secondThingGroupResponse = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
        // Cleanup all IoT thing resources we created
        cleanup();
        IotJobsUtils.cleanThingGroup(iotClient, secondThingGroupResponse.thingGroupName());
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
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
                DeploymentService.GROUP_TO_ROOT_PACKAGES_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        // Second deployment to remove some services deployed previously
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(secondThingGroupResponse.thingGroupName())
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(kernel.locate("SomeService")::getState, eventuallyEval(is(State.FINISHED)));
    }

}
