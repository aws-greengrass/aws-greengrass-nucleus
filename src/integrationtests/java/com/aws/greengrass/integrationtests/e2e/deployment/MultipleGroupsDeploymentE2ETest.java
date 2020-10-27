/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(GGExtension.class)
@Tag("E2E")
class MultipleGroupsDeploymentE2ETest extends BaseE2ETestCase {

    private CreateThingGroupResponse secondThingGroupResponse;

    public MultipleGroupsDeploymentE2ETest() throws Exception {
        super();
    }

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
        cleanup();
    }

    @BeforeEach
    void launchKernel() throws Exception {
        initKernel();
        kernel.launch();
        secondThingGroupResponse = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        createdThingGroups.add(secondThingGroupResponse.thingGroupName());

        // GG_NEEDS_REVIEW: TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        Thread.sleep(10_000);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_WHEN_deployment_to_2_groups_THEN_both_deployments_succeed_and_service_in_both_group_finished() throws Exception {

        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(secondThingGroupResponse.thingGroupName())
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("SomeService")::getState, eventuallyEval(is(State.FINISHED)));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_to_2_groups_WHEN_both_deployments_have_same_service_different_version_THEN_second_deployment_fails_due_to_conflict(
            ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, ExecutionException.class);

        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
                DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(secondThingGroupResponse.thingGroupName())
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.FAILED));

        assertThat("Incorrect component version running",
                getCloudDeployedComponent("CustomerApp").getServiceConfig().find(VERSION_CONFIG_KEY).getOnce().toString(),
                is("0.9.1"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_to_2_groups_WHEN_remove_common_service_from_1_group_THEN_service_keeps_running() throws Exception {

        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
                DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(secondThingGroupResponse.thingGroupName())
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        SetConfigurationRequest setRequest3 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult3 = setAndPublishFleetConfiguration(setRequest3);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult3.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat("Incorrect component version running",
                getCloudDeployedComponent("CustomerApp").getServiceConfig().find(VERSION_CONFIG_KEY).getOnce().toString(),
                is("0.9.1"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_to_2_groups_WHEN_remove_service_from_1_group_THEN_service_is_removed () throws Exception {

        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
                DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(secondThingGroupResponse.thingGroupName())
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        SetConfigurationRequest setRequest3 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"));
        PublishConfigurationResult publishResult3 = setAndPublishFleetConfiguration(setRequest3);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult3.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> {
            GreengrassService service = getCloudDeployedComponent("SomeService");
            logger.atInfo().log("Service is " + service.getName());
        });
    }
}
