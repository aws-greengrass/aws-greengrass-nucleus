/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotshadow.IotShadowClient;
import software.amazon.awssdk.iot.iotshadow.model.UpdateNamedShadowSubscriptionRequest;
import software.amazon.awssdk.services.greengrassv2.model.ComponentConfigurationUpdate;
import software.amazon.awssdk.services.greengrassv2.model.ComponentDeploymentSpecification;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.ShadowDeploymentListener.DEPLOYMENT_SHADOW_NAME;
import static com.aws.greengrass.deployment.ThingGroupHelper.THING_GROUP_RESOURCE_TYPE_PREFIX;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.status.model.DeploymentInformation.STATUS_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void GIVEN_kernel_running_WHEN_deployment_to_2_groups_THEN_both_deployments_succeed_and_service_in_both_group_finished()
            throws Exception {

        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").configurationUpdate(
                                ComponentConfigurationUpdate.builder().merge("{\"sampleText\":\"FCS integ test\"}")
                                        .build()).build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CreateDeploymentRequest createDeploymentRequest2 =
                CreateDeploymentRequest.builder().targetArn(secondThingGroupResponse.thingGroupArn()).components(
                        Utils.immutableMap("SomeService",
                                ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
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

        ignoreExceptionOfType(context,
                NoAvailableComponentVersionException.class); // Expect this to happen due to conflict

        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.1").configurationUpdate(
                                ComponentConfigurationUpdate.builder().merge("{\"sampleText\":\"FCS integ test\"}")
                                        .build()).build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        CreateDeploymentRequest createDeploymentRequest2 =
                CreateDeploymentRequest.builder().targetArn(secondThingGroupResponse.thingGroupArn()).components(
                        Utils.immutableMap("CustomerApp",
                                ComponentDeploymentSpecification.builder().componentVersion("1.0.0")
                                        .configurationUpdate(ComponentConfigurationUpdate.builder()
                                                .merge("{\"sampleText\":\"FCS integ test\"}").build()).build())).build();
        CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.FAILED));

        assertThat("Incorrect component version running",
                getCloudDeployedComponent("CustomerApp").getServiceConfig().find(VERSION_CONFIG_KEY).getOnce()
                        .toString(), is("0.9.1"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_to_2_groups_WHEN_remove_common_service_from_1_group_THEN_service_keeps_running()
            throws Exception {

        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.1").configurationUpdate(
                                ComponentConfigurationUpdate.builder().merge("{\"sampleText\":\"FCS integ test\"}")
                                        .build()).build(), "SomeService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        CreateDeploymentRequest createDeploymentRequest2 =
                CreateDeploymentRequest.builder().targetArn(secondThingGroupResponse.thingGroupArn()).components(
                        Utils.immutableMap("CustomerApp",
                                ComponentDeploymentSpecification.builder().componentVersion("0.9.1")
                                        .configurationUpdate(ComponentConfigurationUpdate.builder()
                                                .merge("{\"sampleText\":\"FCS integ test\"}").build()).build())).build();
        CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CreateDeploymentRequest createDeploymentRequest3 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("SomeService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result3 = draftAndCreateDeployment(createDeploymentRequest3);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result3.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat("Incorrect component version running",
                getCloudDeployedComponent("CustomerApp").getServiceConfig().find(VERSION_CONFIG_KEY).getOnce()
                        .toString(), is("0.9.1"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_to_2_groups_WHEN_remove_service_from_1_group_THEN_service_is_removed() throws Exception {

        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.1").configurationUpdate(
                                ComponentConfigurationUpdate.builder().merge("{\"sampleText\":\"FCS integ test\"}")
                                        .build()).build(), "SomeService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Topics groupToRootMapping = kernel.getConfig().lookupTopics(DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);
        logger.atInfo().log("Group to root mapping is: " + groupToRootMapping.toString());

        CreateDeploymentRequest createDeploymentRequest2 =
                CreateDeploymentRequest.builder().targetArn(secondThingGroupResponse.thingGroupArn()).components(
                        Utils.immutableMap("CustomerApp",
                                ComponentDeploymentSpecification.builder().componentVersion("0.9.1")
                                        .configurationUpdate(ComponentConfigurationUpdate.builder()
                                                .merge("{\"sampleText\":\"FCS integ test\"}").build()).build())).build();
        CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CreateDeploymentRequest createDeploymentRequest3 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.1").configurationUpdate(
                                ComponentConfigurationUpdate.builder().merge("{\"sampleText\":\"FCS integ test\"}")
                                        .build()).build())).build();
        CreateDeploymentResponse result3 = draftAndCreateDeployment(createDeploymentRequest3);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result3.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> {
            GreengrassService service = getCloudDeployedComponent("SomeService");
            logger.atInfo().log("Service is " + service.getName());
        });
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_2_multiple_groups_WHEN_device_removed_from_group_THEN_components_removed_on_next_deployment() throws Exception {
        CreateDeploymentRequest deploymentToFirstGroup = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse firstDeploymentResult = draftAndCreateDeployment(deploymentToFirstGroup);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, firstDeploymentResult.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CreateDeploymentRequest deviceDeployment = CreateDeploymentRequest.builder().targetArn(thingInfo.getThingArn())
                .components(Utils.immutableMap("SomeService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CountDownLatch deviceDeploymentSucceeded = listenToShadowDeploymentUpdates();
        draftAndCreateDeployment(deviceDeployment);
        deviceDeploymentSucceeded.await(5, TimeUnit.MINUTES);

        CreateThingGroupResponse thirdThingGroup = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        createdThingGroups.add(thirdThingGroup.thingGroupName());

        CreateDeploymentRequest deploymentToThirdGroup = CreateDeploymentRequest.builder().targetArn(thirdThingGroup.thingGroupArn())
                .components(Utils.immutableMap("YellowSignal",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse thirdDeploymentResult = draftAndCreateDeployment(deploymentToThirdGroup);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, thirdDeploymentResult.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        IotJobsUtils.removeFromThingGroup(iotClient, thingInfo, thirdThingGroup.thingGroupArn());

        CreateThingGroupResponse fourthThingGroup = IotJobsUtils.createThingGroupAndAddThing(iotClient, thingInfo);
        createdThingGroups.add(fourthThingGroup.thingGroupName());

        CreateDeploymentRequest deploymentToFourthGroup = CreateDeploymentRequest.builder().targetArn(fourthThingGroup.thingGroupArn())
                .components(Utils.immutableMap("RedSignal",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse fourthDeploymentResult = draftAndCreateDeployment(deploymentToFourthGroup);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, fourthDeploymentResult.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        Map<GreengrassService, DependencyType> dependenciesAfter = kernel.getMain().getDependencies();
        List<String> serviceNames = dependenciesAfter.keySet().stream().map(service -> service.getName()).collect(Collectors.toList());
        assertTrue(serviceNames.containsAll(Arrays.asList(getTestComponentNameInCloud("CustomerApp"),
                getTestComponentNameInCloud("SomeService"), getTestComponentNameInCloud("RedSignal"))));
        assertFalse(serviceNames.containsAll(Arrays.asList(getTestComponentNameInCloud("YellowSignal"))));

        Topics groupToRootTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                GROUP_TO_ROOT_COMPONENTS_TOPICS);
        assertNotNull(groupToRootTopic.findTopics(THING_GROUP_RESOURCE_TYPE_PREFIX + thingGroupName, getTestComponentNameInCloud("CustomerApp")));
        assertNotNull(groupToRootTopic.findTopics("thing/" + thingInfo.getThingName(), getTestComponentNameInCloud("SomeService")));
        assertNotNull(groupToRootTopic.findTopics(THING_GROUP_RESOURCE_TYPE_PREFIX + fourthThingGroup.thingGroupName(), getTestComponentNameInCloud("RedSignal")));

        assertNull(groupToRootTopic.findTopics(THING_GROUP_RESOURCE_TYPE_PREFIX + thirdThingGroup.thingGroupName()));

        Topics componentsToGroupTopic = kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC, DEPLOYMENT_SERVICE_TOPICS,
                COMPONENTS_TO_GROUPS_TOPICS);

        assertNotNull(componentsToGroupTopic.findTopics(getTestComponentNameInCloud("CustomerApp")));
        assertNotNull(componentsToGroupTopic.findTopics(getTestComponentNameInCloud("SomeService")));
        assertNotNull(componentsToGroupTopic.findTopics(getTestComponentNameInCloud("RedSignal")));

        assertNull(componentsToGroupTopic.findTopics(getTestComponentNameInCloud("YellowSignal")));

    }


    private CountDownLatch listenToShadowDeploymentUpdates(){
        IotShadowClient shadowClient =
                new IotShadowClient(new WrapperMqttClientConnection(kernel.getContext().get(MqttClient.class)));

        UpdateNamedShadowSubscriptionRequest req = new UpdateNamedShadowSubscriptionRequest();
        req.shadowName = DEPLOYMENT_SHADOW_NAME;
        req.thingName = thingInfo.getThingName();

        CountDownLatch reportSucceededCdl = new CountDownLatch(1);
        shadowClient.SubscribeToUpdateNamedShadowAccepted(req, QualityOfService.AT_LEAST_ONCE, (response) -> {
            if (response.state.reported == null) {
                return;
            }
            String reportedStatus = (String) response.state.reported.get(STATUS_KEY);
            if (JobStatus.SUCCEEDED.toString().equals(reportedStatus)) {
                reportSucceededCdl.countDown();
            }
        });
        return reportSucceededCdl;
    }
}
