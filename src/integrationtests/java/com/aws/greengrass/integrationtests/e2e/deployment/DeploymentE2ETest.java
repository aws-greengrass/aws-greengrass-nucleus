/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.ComponentInfo;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicy;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.amazonaws.services.evergreen.model.ConfigurationUpdate;
import com.amazonaws.services.evergreen.model.ConfigurationValidationPolicy;
import com.amazonaws.services.evergreen.model.CreateDeploymentRequest;
import com.amazonaws.services.evergreen.model.CreateDeploymentResult;
import com.amazonaws.services.evergreen.model.DeploymentPolicies;
import com.amazonaws.services.evergreen.model.FailureHandlingPolicy;
import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.ResourceNotFoundException;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.ipc.IPCClientImpl;
import com.aws.greengrass.ipc.config.KernelIPCClientConfig;
import com.aws.greengrass.ipc.services.lifecycle.Lifecycle;
import com.aws.greengrass.ipc.services.lifecycle.LifecycleImpl;
import com.aws.greengrass.ipc.services.lifecycle.PreComponentUpdateEvent;
import com.aws.greengrass.ipc.services.lifecycle.exceptions.LifecycleIPCException;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.UpdateSystemSafelyService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.collection.IsMapWithSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotshadow.IotShadowClient;
import software.amazon.awssdk.iot.iotshadow.model.GetShadowRequest;
import software.amazon.awssdk.iot.iotshadow.model.GetShadowSubscriptionRequest;
import software.amazon.awssdk.iot.iotshadow.model.UpdateShadowSubscriptionRequest;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.integrationtests.ipc.IPCTestUtils.getIPCConfigForService;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
@Tag("E2E")
class DeploymentE2ETest extends BaseE2ETestCase {
    private CountDownLatch stdoutCountdown;


    protected DeploymentE2ETest() throws Exception {
        super();
    }

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

        // GG_NEEDS_REVIEW: TODO: Without this sleep, DeploymentService sometimes is not able to pick up new IoT job created here,
        // causing these tests to fail. There may be a race condition between DeploymentService startup logic and
        // creating new IoT job here.
        Thread.sleep(10_000);
        setDeviceConfig(kernel, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);
    }

    @Test
    void GIVEN_kernel_running_WHEN_device_deployment_adds_packages_THEN_new_services_should_be_running() throws Exception {
        CountDownLatch cdlDeploymentFinished = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().contains("Current deployment finished")) {
                cdlDeploymentFinished.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);
        SetConfigurationRequest setRequest = new SetConfigurationRequest()
                .withTargetName(thingInfo.getThingName())
                .withTargetType(THING_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                .addPackagesEntry("SomeService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));

        setAndPublishFleetConfiguration(setRequest);
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));

        assertTrue(cdlDeploymentFinished.await(5, TimeUnit.MINUTES));

        Slf4jLogAdapter.removeGlobalListener(listener);
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("SomeService")::getState, eventuallyEval(is(State.FINISHED)));

        IotShadowClient shadowClient = new IotShadowClient(
                new WrapperMqttClientConnection(kernel.getContext().get(MqttClient.class)));

        CountDownLatch getShadowCDL = new CountDownLatch(1);
        GetShadowSubscriptionRequest request = new GetShadowSubscriptionRequest();
        request.thingName = thingInfo.getThingName();
        shadowClient.SubscribeToGetShadowAccepted(request, QualityOfService.AT_LEAST_ONCE, (response) -> {
            //verify desired and reported state are same
            assertEquals(response.state.desired, response.state.reported);
            getShadowCDL.countDown();
        });

        UpdateShadowSubscriptionRequest req = new UpdateShadowSubscriptionRequest();
        req.thingName = thingInfo.getThingName();
        CountDownLatch updateShadowCDL = new CountDownLatch(1);
        shadowClient.SubscribeToUpdateShadowAccepted(req, QualityOfService.AT_LEAST_ONCE , (response) -> {
            updateShadowCDL.countDown();
        });
        // wait for the shadow's reported section to be updated
        updateShadowCDL.await(30, TimeUnit.SECONDS);
        GetShadowRequest request1 = new GetShadowRequest();
        request1.thingName = thingInfo.getThingName();
        //get shadow to verify that desired and reported section are in sync
        shadowClient.PublishGetShadow(request1, QualityOfService.AT_LEAST_ONCE).get();
        getShadowCDL.await(30, TimeUnit.SECONDS);

    }


    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_target_service_has_dependencies_WHEN_deploys_target_service_THEN_service_and_dependencies_should_be_deployed()
            throws Exception {

        // Set up stdout listener to capture stdout for verify interpolation
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            Map<String, String> contexts = m.getContexts();
            String messageOnStdout = contexts.get("stdout");
            if (messageOnStdout != null && messageOnStdout.contains(
                    "CustomerApp output.")) {
                stdouts.add(messageOnStdout);
                stdoutCountdown.countDown(); // countdown when received output to verify
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        stdoutCountdown = new CountDownLatch(1);
        // 1st Deployment to have some services running in Kernel with default configuration
        CreateDeploymentRequest createDeployment1 =
                new CreateDeploymentRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addComponentsEntry("CustomerApp",
                                          new ComponentInfo().withVersion("1.0.0"));

//        SetConfigurationRequest setRequest1 =
//                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
//                        .addPackagesEntry("CustomerApp",
//                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        CreateDeploymentResult createDeploymentResult1 = draftAndCreateDeployment(createDeployment1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("Mosquitto")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(getCloudDeployedComponent("GreenSignal")::getState, eventuallyEval(is(State.FINISHED)));

        // verify config in kernel
        Map<String, Object> resultConfig = getCloudDeployedComponent("CustomerApp").getServiceConfig().findTopics(
                KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();

        assertThat(resultConfig, IsMapWithSize.aMapWithSize(3));

        assertThat(resultConfig, IsMapContaining.hasEntry("sampleText", "This is a test"));
        assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Arrays.asList("item1", "item2")));
        assertThat(resultConfig, IsMapContaining.hasKey("path"));
        assertThat((Map<String, String>) resultConfig.get("path"),
                   IsMapContaining.hasEntry("leafKey", "default value of /path/leafKey"));

        // verify stdout
        assertThat("The stdout should be captured within seconds.", stdoutCountdown.await(5, TimeUnit.SECONDS));

        String customerAppStdout = stdouts.get(0);
        assertThat(customerAppStdout, containsString("This is a test"));
        assertThat(customerAppStdout, containsString("Value for /path/leafKey: default value of /path/leafKey."));
        assertThat(customerAppStdout, containsString("Value for /listKey/0: item1."));
        assertThat(customerAppStdout, containsString("Value for /newKey: {configuration:/newKey}"));

        // reset countdown and stdouts
        stdoutCountdown = new CountDownLatch(1);
        stdouts.clear();

        // 2nd deployment to merge
        /*
         * {
         *   "MERGE": {
         *     "sampleText": "updated value for sampleText",
         *     "listKey": [
         *       "item3"
         *     ],
         *     "path": {
         *       "leafKey": "updated value of /path/leafKey"
         *     }
         *   }
         * }
         */
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode configUpdateInNode = mapper.createObjectNode();
        ObjectNode mergeNode = configUpdateInNode.with("MERGE");
        mergeNode.put("sampleText", "updated");
        mergeNode.put("newKey", "updated");

        mergeNode.withArray("listKey").add("item3");
        mergeNode.with("path").put("leafKey", "updated");

//        String configUpdateJson = mapper.writeValueAsString(configUpdateInNode);

        CreateDeploymentRequest createDeployment2 =
                new CreateDeploymentRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addComponentsEntry("CustomerApp",
                                            new ComponentInfo().withVersion("1.0.0").withConfigurationUpdate(
                                                    new ConfigurationUpdate().withMerge(mapper.writeValueAsString(mergeNode))));

//        SetConfigurationRequest setRequest2 =
//                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
//                        .addPackagesEntry("CustomerApp",
//                                          new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
//                                                  .withConfiguration(configUpdateJson));

        CreateDeploymentResult createDeploymentResult2 = draftAndCreateDeployment(createDeployment2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult2.getJobId(), thingInfo.getThingName(),
                                                        Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("Mosquitto")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(getCloudDeployedComponent("GreenSignal")::getState, eventuallyEval(is(State.FINISHED)));

        // verify config in kernel
        resultConfig = getCloudDeployedComponent("CustomerApp").getServiceConfig().findTopics(
                KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
        assertThat(resultConfig, IsMapWithSize.aMapWithSize(4));

        assertThat(resultConfig, IsMapContaining.hasEntry("sampleText", "updated"));
        assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Collections.singletonList("item3")));
        assertThat(resultConfig, IsMapContaining.hasKey("path"));
        assertThat((Map<String, String>) resultConfig.get("path"),
                   IsMapContaining.hasEntry("leafKey", "updated"));

        // verify stdout
        assertThat("The stdout should be captured within seconds.", stdoutCountdown.await(5, TimeUnit.SECONDS));

        customerAppStdout = stdouts.get(0);
        assertThat(customerAppStdout, containsString("Value for /sampleText: updated"));
        assertThat(customerAppStdout, containsString("Value for /path/leafKey: updated"));
        assertThat(customerAppStdout, containsString("Value for /listKey/0: item3."));
        assertThat(customerAppStdout, containsString("Value for /newKey: updated"));


        // reset countdown and stdouts
        stdoutCountdown = new CountDownLatch(1);
        stdouts.clear();

        // 3rd deployment to reset
        CreateDeploymentRequest createDeployment3 =
                new CreateDeploymentRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addComponentsEntry("CustomerApp",
                                            new ComponentInfo().withVersion("1.0.0").withConfigurationUpdate(
                                                    new ConfigurationUpdate().withReset("/sampleText", "/path")));


//        SetConfigurationRequest setRequest3 =
//                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
//                        .addPackagesEntry("CustomerApp",
//                                          new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
//                                                  .withConfiguration("{\"RESET\": [\"/sampleText\", \"/path\"]}"));

//        PublishConfigurationResult publishResult3 = setAndPublishFleetConfiguration(setRequest3);
        CreateDeploymentResult createDeploymentResult = draftAndCreateDeployment(createDeployment3);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult.getJobId(), thingInfo.getThingName(),
                                                        Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("Mosquitto")::getState, eventuallyEval(is(State.RUNNING)));
        assertThat(getCloudDeployedComponent("GreenSignal")::getState, eventuallyEval(is(State.FINISHED)));

        // verify config in kernel
        resultConfig = getCloudDeployedComponent("CustomerApp").getServiceConfig().findTopics(
                KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
        assertThat(resultConfig, IsMapWithSize.aMapWithSize(4));

        assertThat(resultConfig, IsMapContaining.hasEntry("sampleText", "This is a test"));
        assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Collections.singletonList("item3")));
        assertThat(resultConfig, IsMapContaining.hasKey("path"));
        assertThat((Map<String, String>) resultConfig.get("path"),
                   IsMapContaining.hasEntry("leafKey", "default value of /path/leafKey"));

        // verify stdout
        assertThat("The stdout should be captured within seconds.", stdoutCountdown.await(5, TimeUnit.SECONDS));

        customerAppStdout = stdouts.get(0);
        assertThat(customerAppStdout, containsString("Value for /sampleText: This is a test"));
        assertThat(customerAppStdout, containsString("Value for /path/leafKey: default value of /path/leafKey"));
        assertThat(customerAppStdout, containsString("Value for /listKey/0: item3."));
        assertThat(customerAppStdout, containsString("Value for /newKey: updated"));

        // cleanup
        Slf4jLogAdapter.removeGlobalListener(listener);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_removes_packages_THEN_services_should_be_stopped_and_job_is_successful()
            throws Exception {
        // First Deployment to have some services running in Kernel which can be removed later
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0")
                                        .withConfiguration("{\"sampleText\":\"FCS integ test\"}"))
                        .addPackagesEntry("SomeService",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Second deployment to remove some services deployed previously
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("SomeService").getState());
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deployment_has_conflicts_THEN_job_should_fail_and_return_error(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ResourceNotFoundException.class);

        // New deployment contains dependency conflicts
        SetConfigurationRequest setRequest =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("SomeOldService",
                                new PackageMetaData().withRootComponent(true).withVersion("0.9.0"))
                        .addPackagesEntry("SomeService",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult = setAndPublishFleetConfiguration(setRequest);

        String jobId = publishResult.getJobId();
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.FAILED));

        // Make sure IoT Job was marked as failed and provided correct reason
        String deploymentError = iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thingInfo.getThingName()).build())
                .execution().statusDetails().detailsMap().get("error");
        assertThat(deploymentError,
                containsString("com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException"));
        assertThat(deploymentError, containsString(getTestComponentNameInCloud("Mosquitto")));
        assertThat(deploymentError,
                containsString(getTestComponentNameInCloud("SomeService") + "==1.0.0"));
        assertThat(deploymentError,
                containsString(getTestComponentNameInCloud("SomeOldService") + "==0.9.0"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_deploy_fix_THEN_service_run_and_job_is_successful(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context,
                "Service " + getTestComponentNameInCloud("CustomerApp") + " in broken state after deployment");

        // Create first Job Doc with a faulty service (CustomerApp-0.9.0)
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .withDeploymentPolicies(new DeploymentPolicies()
                                .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                                .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                        .withAction(ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS)
                                        .withTimeout(120)).withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING))
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("0.9.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        // Wait for deployment job to fail after three retries of starting CustomerApp
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(7), s -> s.equals(JobExecutionStatus.FAILED));
        // CustomerApp should be in BROKEN state
        assertEquals(State.BROKEN, getCloudDeployedComponent("CustomerApp").getState());

        // Create another job with a fix to the faulty service (CustomerApp-0.9.1).
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("0.9.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        // Ensure that main is FINISHED and CustomerApp is RUNNING.
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertEquals(State.RUNNING, getCloudDeployedComponent("CustomerApp").getState());
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_failure_policy_is_rollback_THEN_deployment_is_rolled_back_and_job_fails(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context,
                "Service " + getTestComponentNameInCloud("CustomerApp") + " in broken state after deployment");

        // Deploy some services that can be used for verification later
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("RedSignal",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                        .addPackagesEntry("YellowSignal",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Create a Job Doc with a faulty service (CustomerApp-0.9.0) requesting rollback on failure
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .withDeploymentPolicies(new DeploymentPolicies()
                                .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                                .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                        .withAction(ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS)
                                        .withTimeout(120)).withFailureHandlingPolicy(FailureHandlingPolicy.ROLLBACK))
                        .addPackagesEntry("RedSignal",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                        .addPackagesEntry("YellowSignal",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("0.9.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        String jobId2 = publishResult2.getJobId();
        IotJobsUtils
                .waitForJobExecutionStatusToSatisfy(iotClient, jobId2, thingInfo.getThingName(), Duration.ofMinutes(5),
                        s -> s.equals(JobExecutionStatus.FAILED));

        // Main should be INSTALLED state and CustomerApp should be stopped and removed
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("RedSignal")::getState,
                eventuallyEval(is(State.FINISHED), Duration.ofSeconds(10L)));
        assertThat(getCloudDeployedComponent("YellowSignal")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("CustomerApp").getState());
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("Mosquitto").getState());
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("GreenSignal").getState());

        // IoT Job should have failed with correct message.
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE.name(), iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thingInfo.getThingName()).build())
                .execution().statusDetails().detailsMap().get("detailed-deployment-status"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_some_running_services_WHEN_cancel_event_received_and_kernel_is_waiting_for_safe_time_THEN_deployment_should_be_canceled()
            throws Exception {
        // First Deployment to have a service running in Kernel which has a safety check that always returns
        // false, i.e. keeps waiting forever
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("NonDisruptableService",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        KernelIPCClientConfig nonDisruptable =
                getIPCConfigForService(getTestComponentNameInCloud("NonDisruptableService"), kernel);
        IPCClientImpl ipcClient = new IPCClientImpl(nonDisruptable);
        Lifecycle lifecycle = new LifecycleImpl(ipcClient);

        lifecycle.subscribeToComponentUpdate((event) -> {
            if (event instanceof PreComponentUpdateEvent) {
                try {
                    lifecycle.deferComponentUpdate("NonDisruptableService", TimeUnit.SECONDS.toMillis(60));
                    ipcClient.disconnect();
                } catch (LifecycleIPCException e) {
                }
            }
        });

        // Second deployment to update the service which is currently running an important task so deployment should
        // wait for a safe time to update
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .withDeploymentPolicies(new DeploymentPolicies()
                                .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                                .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                        .withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS).withTimeout(120)))
                        .addPackagesEntry("NonDisruptableService",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        CountDownLatch updateRegistered = new CountDownLatch(1);
        CountDownLatch deploymentCancelled = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> logListener = m -> {
            if ("register-service-update-action".equals(m.getEventType())) {
                updateRegistered.countDown();
            }
            if (m.getMessage() != null && m.getMessage().contains("Deployment was cancelled")) {
                deploymentCancelled.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

        // Wait for the second deployment to start waiting for safe time to update and
        // then cancel it's corresponding job from cloud
        assertTrue(updateRegistered.await(60, TimeUnit.SECONDS));
        assertTrue(kernel.getContext().get(UpdateSystemSafelyService.class)
                .hasPendingUpdateAction(publishResult2.getConfigurationArn()));

        // GG_NEEDS_REVIEW: TODO : Call Fleet configuration service's cancel API when ready instead of calling IoT Jobs API
        IotJobsUtils.cancelJob(iotClient, publishResult2.getJobId());

        // Wait for indication that cancellation has gone through
        assertTrue(deploymentCancelled.await(60, TimeUnit.SECONDS));
        assertFalse(kernel.getContext().get(UpdateSystemSafelyService.class)
                .hasPendingUpdateAction(publishResult2.getConfigurationArn()));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("NonDisruptableService")::getState, eventuallyEval(is(State.RUNNING)));
        assertEquals("1.0.0", getCloudDeployedComponent("NonDisruptableService").getConfig().find("version").getOnce());

        Slf4jLogAdapter.removeGlobalListener(logListener);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_received_WHEN_skip_safety_check_THEN_safety_check_skipped() throws Exception {
        // GIVEN
        // First Deployment to have a service running in Kernel which has a safety check that always returns
        // false, i.e. keeps waiting forever
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("NonDisruptableService",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CountDownLatch safeCheckSkipped = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> logListener = m -> {
            if (m.getMessage().contains("Deployment is configured to skip safety check")) {
                safeCheckSkipped.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);

        // WHEN
        // Second deployment to update the service with SKIP_NOTIFY_COMPONENTS
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .withDeploymentPolicies(new DeploymentPolicies()
                                .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).withComponentUpdatePolicy(
                                        new ComponentUpdatePolicy()
                                                .withAction(ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS)
                                                .withTimeout(120))).addPackagesEntry("NonDisruptableService",
                        new PackageMetaData().withRootComponent(true).withVersion("1.0.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

        // THEN
        assertTrue(safeCheckSkipped.await(60, TimeUnit.SECONDS));
        Slf4jLogAdapter.removeGlobalListener(logListener);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("NonDisruptableService")::getState, eventuallyEval(is(State.RUNNING)));
        assertEquals("1.0.1", getCloudDeployedComponent("NonDisruptableService").getConfig().find("version").getOnce());
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_in_progress_with_more_jobs_queued_in_cloud_WHEN_cancel_event_received_and_kernel_is_waiting_for_safe_time_THEN_deployment_should_be_canceled()
            throws Exception {
        // First Deployment to have a service running in Kernel which has a safety check that always returns
        // false, i.e. keeps waiting forever
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("NonDisruptableService",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        KernelIPCClientConfig nonDisruptable =
                getIPCConfigForService(getTestComponentNameInCloud("NonDisruptableService"), kernel);
        IPCClientImpl ipcClient = new IPCClientImpl(nonDisruptable);
        Lifecycle lifecycle = new LifecycleImpl(ipcClient);

        lifecycle.subscribeToComponentUpdate((event) -> {
            if (event instanceof PreComponentUpdateEvent) {
                try {
                    lifecycle.deferComponentUpdate("NonDisruptableService", TimeUnit.SECONDS.toMillis(60));
                    ipcClient.disconnect();
                } catch (LifecycleIPCException e) {
                }
            }
        });

        CountDownLatch updateRegistered = new CountDownLatch(1);
        CountDownLatch deploymentCancelled = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> logListener = m -> {
            if ("register-service-update-action".equals(m.getEventType())) {
                updateRegistered.countDown();
            }
            if (m.getMessage() != null && m.getMessage().contains("Deployment was cancelled")) {
                deploymentCancelled.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);

        // Second deployment to update the service which is currently running an important task so deployment should
        // keep waiting for a safe time to update
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .withDeploymentPolicies(new DeploymentPolicies()
                                .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).withComponentUpdatePolicy(
                                        new ComponentUpdatePolicy()
                                                .withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS)
                                                .withTimeout(120))).addPackagesEntry("NonDisruptableService",
                        new PackageMetaData().withRootComponent(true).withVersion("1.0.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

        // Create one more deployment so that it's queued in cloud
        SetConfigurationRequest setRequest3 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .withDeploymentPolicies(new DeploymentPolicies()
                                .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                                .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING).withComponentUpdatePolicy(
                                        new ComponentUpdatePolicy()
                                                .withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS)
                                                .withTimeout(120))).addPackagesEntry("NonDisruptableService",
                        new PackageMetaData().withRootComponent(true).withVersion("1.0.1"));
        PublishConfigurationResult publishResult3 = setAndPublishFleetConfiguration(setRequest3);

        // Wait for the second deployment to start waiting for safe time to update and
        // then cancel it's corresponding job from cloud
        assertTrue(updateRegistered.await(60, TimeUnit.SECONDS));
        assertTrue(kernel.getContext().get(UpdateSystemSafelyService.class)
                .hasPendingUpdateAction(publishResult2.getConfigurationArn()));

        // GG_NEEDS_REVIEW: TODO : Call Fleet configuration service's cancel API when ready instead of calling IoT Jobs API
        IotJobsUtils.cancelJob(iotClient, publishResult2.getJobId());

        // Wait for indication that cancellation has gone through
        assertTrue(deploymentCancelled.await(240, TimeUnit.SECONDS));
        assertFalse(kernel.getContext().get(UpdateSystemSafelyService.class)
                .hasPendingUpdateAction(publishResult2.getConfigurationArn()));

        // Now that we've verified that the job got cancelled, let's verify that the next job was picked up
        // and put into IN_PROGRESS state
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult3.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("NonDisruptableService")::getState, eventuallyEval(is(State.RUNNING)));
        assertEquals("1.0.0", getCloudDeployedComponent("NonDisruptableService").getConfig().find("version").getOnce());

        Slf4jLogAdapter.removeGlobalListener(logListener);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_updating_Component_WHEN_removing_field_from_recipe_THEN_kernel_config_remove_corresponding_field()
            throws Exception {
        // CustomerApp 0.9.1 has 'startup' key in lifecycle
        SetConfigurationRequest setRequest1 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("0.9.1"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(10), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        GreengrassService customerApp = getCloudDeployedComponent("CustomerApp");
        assertNotNull(customerApp.getConfig().findTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC).getChild("startup"));

        // update with some local data
        customerApp.getRuntimeConfig().lookup("runtimeKey").withValue("val");

        // Second deployment to update CustomerApp, replace 'startup' key with 'run' key.
        SetConfigurationRequest setRequest2 =
                new SetConfigurationRequest().withTargetName(thingGroupName).withTargetType(THING_GROUP_TARGET_TYPE)
                        .addPackagesEntry("CustomerApp",
                                new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        customerApp = getCloudDeployedComponent("CustomerApp");
        // assert local data is not affected
        assertEquals("val", customerApp.getRuntimeConfig().findLeafChild("runtimeKey").getOnce());
        // assert updated service have 'startup' key removed.
        assertNotNull(customerApp.getConfig().findTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC).getChild("run"));
        assertNull(customerApp.getConfig().findTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC).getChild("startup"));
        assertThat(customerApp::getState, eventuallyEval(is(State.FINISHED)));
    }
}
