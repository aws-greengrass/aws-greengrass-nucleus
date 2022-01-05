/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.integrationtests.e2e.BaseE2ETestCase;
import com.aws.greengrass.integrationtests.e2e.util.IotJobsUtils;
import com.aws.greengrass.integrationtests.ipc.IPCTestUtils;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.UpdateSystemPolicyService;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.hamcrest.collection.IsCollectionWithSize;
import org.hamcrest.collection.IsMapContaining;
import org.hamcrest.collection.IsMapWithSize;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.aws.greengrass.GreengrassCoreIPCClient;
import software.amazon.awssdk.aws.greengrass.model.ComponentUpdatePolicyEvents;
import software.amazon.awssdk.aws.greengrass.model.DeferComponentUpdateRequest;
import software.amazon.awssdk.aws.greengrass.model.SubscribeToComponentUpdatesRequest;
import software.amazon.awssdk.eventstreamrpc.EventStreamRPCConnection;
import software.amazon.awssdk.eventstreamrpc.StreamResponseHandler;
import software.amazon.awssdk.services.greengrassv2.model.ComponentConfigurationUpdate;
import software.amazon.awssdk.services.greengrassv2.model.ComponentDeploymentSpecification;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentRequest;
import software.amazon.awssdk.services.greengrassv2.model.CreateDeploymentResponse;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicy;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentPolicies;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_LIFECYCLE_NAMESPACE_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentFailureHandlingPolicy.DO_NOTHING;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentFailureHandlingPolicy.ROLLBACK;

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

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_target_service_has_dependencies_WHEN_deploys_target_service_THEN_service_and_dependencies_should_be_deployed()
            throws Exception {
        // Set up stdout listener to capture stdout for verify interpolation
        List<String> stdouts = new CopyOnWriteArrayList<>();
        Consumer<GreengrassLogMessage> listener = m -> {
            String messageOnStdout = m.getMessage();
            if (messageOnStdout != null && messageOnStdout.contains("CustomerApp output.")) {
                stdouts.add(messageOnStdout);
                stdoutCountdown.countDown(); // countdown when received output to verify
            }
        };
        try (AutoCloseable l = TestUtils.createCloseableLogListener(listener)) {
            stdoutCountdown = new CountDownLatch(1);
            // 1st Deployment to have some services running in Kernel with default configuration
            CreateDeploymentRequest createDeployment1 = CreateDeploymentRequest.builder().components(
                    Utils.immutableMap("CustomerApp",
                            ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();

            CreateDeploymentResponse createDeploymentResult1 = draftAndCreateDeployment(createDeployment1);

            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult1.iotJobId(),
                    thingInfo.getThingName(), Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));

            assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("Mosquitto")::getState, eventuallyEval(is(State.RUNNING)));
            assertThat(getCloudDeployedComponent("GreenSignal")::getState, eventuallyEval(is(State.FINISHED)));

            // verify config in kernel
            Map<String, Object> resultConfig = getCloudDeployedComponent("CustomerApp").getServiceConfig()
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();

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

            CreateDeploymentRequest createDeployment2 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                    .components(Utils.immutableMap("CustomerApp",
                            ComponentDeploymentSpecification.builder().componentVersion("1.0.0").configurationUpdate(
                                    ComponentConfigurationUpdate.builder().merge(mapper.writeValueAsString(mergeNode))
                                            .build()).build())).build();

            CreateDeploymentResponse createDeploymentResult2 = draftAndCreateDeployment(createDeployment2);
            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult2.iotJobId(),
                    thingInfo.getThingName(), Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));
            assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("Mosquitto")::getState, eventuallyEval(is(State.RUNNING)));
            assertThat(getCloudDeployedComponent("GreenSignal")::getState, eventuallyEval(is(State.FINISHED)));

            // verify config in kernel
            resultConfig = getCloudDeployedComponent("CustomerApp").getServiceConfig()
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
            assertThat(resultConfig, IsMapWithSize.aMapWithSize(4));

            assertThat(resultConfig, IsMapContaining.hasEntry("sampleText", "updated"));
            assertThat(resultConfig, IsMapContaining.hasEntry("listKey", Collections.singletonList("item3")));
            assertThat(resultConfig, IsMapContaining.hasKey("path"));
            assertThat((Map<String, String>) resultConfig.get("path"), IsMapContaining.hasEntry("leafKey", "updated"));

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
            CreateDeploymentRequest createDeployment3 = CreateDeploymentRequest.builder().targetArn(thingGroupArn)
                    .components(Utils.immutableMap("CustomerApp",
                            ComponentDeploymentSpecification.builder().componentVersion("1.0.0").configurationUpdate(
                                    ComponentConfigurationUpdate.builder().reset("/sampleText", "/path").build())
                                    .build())).build();

            CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeployment3);
            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult.iotJobId(),
                    thingInfo.getThingName(), Duration.ofMinutes(2), s -> s.equals(JobExecutionStatus.SUCCEEDED));
            assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("Mosquitto")::getState, eventuallyEval(is(State.RUNNING)));
            assertThat(getCloudDeployedComponent("GreenSignal")::getState, eventuallyEval(is(State.FINISHED)));

            // verify config in kernel
            resultConfig = getCloudDeployedComponent("CustomerApp").getServiceConfig()
                    .findTopics(KernelConfigResolver.CONFIGURATION_CONFIG_KEY).toPOJO();
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

        }
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_kernel_running_with_deployed_services_WHEN_deployment_removes_packages_THEN_services_should_be_stopped_and_job_is_successful()
            throws Exception {
        // First Deployment to have some services running in Kernel which can be removed later
        CreateDeploymentRequest.Builder createDeploymentRequest = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("CustomerApp", ComponentDeploymentSpecification.builder().componentVersion("1.0.0")
                                .configurationUpdate(
                                        ComponentConfigurationUpdate.builder().merge(
                                                "{\"sampleText\":\"FCS integ test\"}")
                                                .build()).build(),
                        "SomeService", ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build()));
        CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest.build());

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult.iotJobId(),
                thingInfo.getThingName(), Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CreateDeploymentRequest createDeploymentRequest2 = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse createDeploymentResult2 = draftAndCreateDeployment(createDeploymentRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult2.iotJobId(),
                thingInfo.getThingName(), Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("SomeService").getState());
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deployment_has_conflicts_THEN_job_should_fail_and_return_error(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, NoAvailableComponentVersionException.class);

        // New deployment contains dependency conflicts
        CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("SomeOldService",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.0").build(), "SomeService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest);
        String jobId = createDeploymentResult.iotJobId();

        IotJobsUtils
                .waitForJobExecutionStatusToSatisfy(iotClient, jobId, thingInfo.getThingName(), Duration.ofMinutes(5),
                        s -> s.equals(JobExecutionStatus.FAILED));

        // Make sure IoT Job was marked as failed and provided correct reason
        String deploymentError = iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thingInfo.getThingName()).build())
                .execution().statusDetails().detailsMap().get(DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY);
        assertThat(deploymentError, containsString("satisfies the requirements"));
        assertThat(deploymentError, containsString(getTestComponentNameInCloud("Mosquitto")));
        assertThat(deploymentError,
                containsString(getTestComponentNameInCloud("SomeService") + " requires =1.0.0"));
        assertThat(deploymentError,
                containsString(getTestComponentNameInCloud("SomeOldService") + " requires =0.9.0"));
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deployment_has_components_that_dont_exist_THEN_job_should_fail_and_return_error(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, NoAvailableComponentVersionException.class);

        // New deployment contains dependency conflicts
        CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("XYZPackage-" + UUID.randomUUID(),
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest);
        String jobId = createDeploymentResult.iotJobId();

        IotJobsUtils
                .waitForJobExecutionStatusToSatisfy(iotClient, jobId, thingInfo.getThingName(), Duration.ofMinutes(5),
                        s -> s.equals(JobExecutionStatus.FAILED));

        // Make sure IoT Job was marked as failed and provided correct reason
        String deploymentError = iotClient.describeJobExecution(
                DescribeJobExecutionRequest.builder().jobId(jobId).thingName(thingInfo.getThingName()).build())
                .execution().statusDetails().detailsMap().get(DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY);
        assertThat(deploymentError, containsString("satisfies the requirements"));
        assertThat(deploymentError, containsString("XYZPackage"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_deploy_fix_THEN_service_run_and_job_is_successful(
            ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context,
                "Service " + getTestComponentNameInCloud("CustomerApp") + " in broken state after deployment");

        // Create first Job Doc with a faulty service (CustomerApp-0.9.0)
        CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().deploymentPolicies(
                DeploymentPolicies.builder().configurationValidationPolicy(
                        DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(120).build())
                        .componentUpdatePolicy(DeploymentComponentUpdatePolicy.builder()
                                .action(SKIP_NOTIFY_COMPONENTS)
                                .timeoutInSeconds(120).build())
                        .failureHandlingPolicy(DO_NOTHING).build())
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.0").build()))
                .build();
        CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest);

        // Wait for deployment job to fail after three retries of starting CustomerApp
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult.iotJobId(),
                thingInfo.getThingName(), Duration.ofMinutes(7), s -> s.equals(JobExecutionStatus.FAILED));
        // CustomerApp should be in BROKEN state
        assertEquals(State.BROKEN, getCloudDeployedComponent("CustomerApp").getState());

        // Create another job with a fix to the faulty service (CustomerApp-0.9.1).
        CreateDeploymentRequest createDeploymentRequest2 = CreateDeploymentRequest.builder()
                .components(Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.1").build())).build();
        CreateDeploymentResponse createDeploymentResult2 = draftAndCreateDeployment(createDeploymentRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult2.iotJobId(),
                thingInfo.getThingName(), Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
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
        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("RedSignal",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build(), "YellowSignal",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Create a Job Doc with a faulty service (CustomerApp-0.9.0) requesting rollback on failure
        CreateDeploymentRequest createDeploymentRequest = CreateDeploymentRequest.builder().deploymentPolicies(
                DeploymentPolicies.builder().configurationValidationPolicy(
                        DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(120).build())
                        .componentUpdatePolicy(DeploymentComponentUpdatePolicy.builder().action(SKIP_NOTIFY_COMPONENTS)
                                .timeoutInSeconds(120).build()).failureHandlingPolicy(ROLLBACK).build()).components(
                Utils.immutableMap("RedSignal",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build(), "YellowSignal",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build(), "CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.0").build())).build();

        CreateDeploymentResponse createDeploymentResult = draftAndCreateDeployment(createDeploymentRequest);

        String jobId2 = createDeploymentResult.iotJobId();
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
                .execution().statusDetails().detailsMap().get(DEPLOYMENT_DETAILED_STATUS_KEY));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_some_running_services_WHEN_cancel_event_received_and_kernel_is_waiting_for_disruptable_time_THEN_deployment_should_be_canceled()
            throws Exception {
        // First Deployment to have a service running in Kernel which has a update policy check that always returns
        // false, i.e. keeps waiting forever
        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder()
                .components(Utils.immutableMap("NonDisruptableService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse createDeploymentResult1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult1.iotJobId(),
                thingInfo.getThingName(), Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CountDownLatch postUpdateEventReceived = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> logListener = null;
        try (EventStreamRPCConnection connection = IPCTestUtils
                .getEventStreamRpcConnection(kernel, "NonDisruptableService" + testComponentSuffix)) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            ipcClient.subscribeToComponentUpdates(new SubscribeToComponentUpdatesRequest(),
                    Optional.of(new StreamResponseHandler<ComponentUpdatePolicyEvents>() {
                        @Override
                        public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                            if (streamEvent.getPreUpdateEvent() != null) {
                                DeferComponentUpdateRequest deferComponentUpdateRequest =
                                        new DeferComponentUpdateRequest();
                                deferComponentUpdateRequest.setRecheckAfterMs(TimeUnit.SECONDS.toMillis(60));
                                deferComponentUpdateRequest.setMessage("NonDisruptableService");
                                // Cannot wait for response inside a callback
                                ipcClient.deferComponentUpdate(deferComponentUpdateRequest, Optional.empty());
                            }
                            if (streamEvent.getPostUpdateEvent() != null) {
                                postUpdateEventReceived.countDown();
                            }
                        }

                        @Override
                        public boolean onStreamError(Throwable error) {
                            logger.atError().setCause(error)
                                    .log("Caught stream error while subscribing for component update");
                            return false;
                        }

                        @Override
                        public void onStreamClosed() {
                        }
                    }));

            // Second deployment to update the service which is currently running an important task so deployment should
             // wait for a disruptable time to update
            CreateDeploymentRequest createDeploymentRequest2 = CreateDeploymentRequest.builder().deploymentPolicies(
                    DeploymentPolicies.builder().failureHandlingPolicy(DO_NOTHING).configurationValidationPolicy(
                            DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(120).build())
                            .componentUpdatePolicy(DeploymentComponentUpdatePolicy.builder().action(NOTIFY_COMPONENTS)
                                    .timeoutInSeconds(120).build()).build()).components(
                    Utils.immutableMap("NonDisruptableService",
                            ComponentDeploymentSpecification.builder().componentVersion("1.0.1").build())).build();
            CreateDeploymentResponse createDeploymentResult2 = draftAndCreateDeployment(createDeploymentRequest2);

            CountDownLatch updateRegistered = new CountDownLatch(1);
            CountDownLatch deploymentCancelled = new CountDownLatch(1);
            logListener = m -> {
                if ("register-service-update-action".equals(m.getEventType())) {
                    updateRegistered.countDown();
                }
                if (m.getMessage() != null && m.getMessage().contains("Deployment was cancelled")) {
                    deploymentCancelled.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(logListener);

            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, createDeploymentResult2.iotJobId(),
                    thingInfo.getThingName(), Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

            // Wait for the second deployment to start waiting for safe time to update and
            // then cancel it's corresponding job from cloud
            assertTrue(updateRegistered.await(60, TimeUnit.SECONDS));
            assertThat("The UpdateSystemService should have one pending action.",
                    kernel.getContext().get(UpdateSystemPolicyService.class).getPendingActions(),
                    IsCollectionWithSize.hasSize(1));

            // GG_NEEDS_REVIEW: TODO : Call Fleet configuration service's cancel API when ready instead of calling IoT Jobs API
            IotJobsUtils.cancelJob(iotClient, createDeploymentResult2.iotJobId());

            // Wait for indication that cancellation has gone through
            assertTrue(deploymentCancelled.await(60, TimeUnit.SECONDS));
            assertThat("The UpdateSystemService's one pending action should be be removed.",
                    kernel.getContext().get(UpdateSystemPolicyService.class).getPendingActions(),
                    IsCollectionWithSize.hasSize(0));
            // Component should be told to resume its work since the change it has been waiting for is cancelled
            assertTrue(postUpdateEventReceived.await(60, TimeUnit.SECONDS));

            // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
            assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("NonDisruptableService")::getState, eventuallyEval(is(State.RUNNING)));
            assertEquals("1.0.0",
                    getCloudDeployedComponent("NonDisruptableService").getConfig().find("version").getOnce());
        } finally {
            if (logListener != null) {
                Slf4jLogAdapter.removeGlobalListener(logListener);
            }
        }
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_received_WHEN_skip_update_policy_check_THEN_update_policy_check_skipped() throws Exception {
        // GIVEN
        // First Deployment to have a service running in Kernel which has a update policy that always returns
        // false, i.e. keeps waiting forever
        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("NonDisruptableService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        CountDownLatch updatePolicyCheckSkipped = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> logListener = m -> {
            if (m.getMessage().contains("Deployment is configured to skip update policy check")) {
                updatePolicyCheckSkipped.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(logListener);

        // WHEN
        // Second deployment to update the service with SKIP_NOTIFY_COMPONENTS
        CreateDeploymentRequest createDeploymentRequest2 = CreateDeploymentRequest.builder().deploymentPolicies(
                DeploymentPolicies.builder().configurationValidationPolicy(
                        DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(120).build())
                        .failureHandlingPolicy(DO_NOTHING).componentUpdatePolicy(
                        DeploymentComponentUpdatePolicy.builder().action(SKIP_NOTIFY_COMPONENTS).timeoutInSeconds(120)
                                .build()).build()).components(Utils.immutableMap("NonDisruptableService",
                ComponentDeploymentSpecification.builder().componentVersion("1.0.1").build())).build();
        CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

        // THEN
        assertTrue(updatePolicyCheckSkipped.await(60, TimeUnit.SECONDS));
        Slf4jLogAdapter.removeGlobalListener(logListener);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
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
        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("NonDisruptableService",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        Consumer<GreengrassLogMessage> logListener = null;

        try (EventStreamRPCConnection connection = IPCTestUtils
                .getEventStreamRpcConnection(kernel, "NonDisruptableService" + testComponentSuffix)) {
            GreengrassCoreIPCClient ipcClient = new GreengrassCoreIPCClient(connection);

            ipcClient.subscribeToComponentUpdates(new SubscribeToComponentUpdatesRequest(),
                    Optional.of(new StreamResponseHandler<ComponentUpdatePolicyEvents>() {
                        @Override
                        public void onStreamEvent(ComponentUpdatePolicyEvents streamEvent) {
                            if (streamEvent.getPreUpdateEvent() != null) {
                                logger.atInfo().log("Got pre component update event");
                                DeferComponentUpdateRequest deferComponentUpdateRequest =
                                        new DeferComponentUpdateRequest();
                                deferComponentUpdateRequest.setRecheckAfterMs(TimeUnit.SECONDS.toMillis(60));
                                deferComponentUpdateRequest.setMessage("NonDisruptableService");
                                logger.atInfo().log("Sending defer request");
                                // Cannot wait inside a callback
                                ipcClient.deferComponentUpdate(deferComponentUpdateRequest, Optional.empty());
                            }
                        }

                        @Override
                        public boolean onStreamError(Throwable error) {
                            logger.atError().setCause(error)
                                    .log("Caught stream error while subscribing for component update");
                            return false;
                        }

                        @Override
                        public void onStreamClosed() {

                        }
                    }));

            CountDownLatch updateRegistered = new CountDownLatch(1);
            CountDownLatch deploymentCancelled = new CountDownLatch(1);
            logListener = m -> {
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
            CreateDeploymentRequest createDeploymentRequest2 = CreateDeploymentRequest.builder().deploymentPolicies(
                    DeploymentPolicies.builder().configurationValidationPolicy(
                            DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(120).build())
                            .failureHandlingPolicy(DO_NOTHING).componentUpdatePolicy(
                            DeploymentComponentUpdatePolicy.builder().action(NOTIFY_COMPONENTS).timeoutInSeconds(120)
                                    .build()).build()).components(Utils.immutableMap("NonDisruptableService",
                    ComponentDeploymentSpecification.builder().componentVersion("1.0.1").build())).build();
            CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);
            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
                    Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

            // Create one more deployment so that it's queued in cloud
            CreateDeploymentRequest createDeploymentRequest3 = CreateDeploymentRequest.builder().deploymentPolicies(
                    DeploymentPolicies.builder().configurationValidationPolicy(
                            DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(120).build())
                            .failureHandlingPolicy(DO_NOTHING).componentUpdatePolicy(
                            DeploymentComponentUpdatePolicy.builder().action(NOTIFY_COMPONENTS).timeoutInSeconds(120)
                                    .build()).build()).components(Utils.immutableMap("NonDisruptableService",
                    ComponentDeploymentSpecification.builder().componentVersion("1.0.1").build())).build();
            CreateDeploymentResponse result3 = draftAndCreateDeployment(createDeploymentRequest3);

            // Wait for the second deployment to start waiting for safe time to update and
            // then cancel it's corresponding job from cloud
            assertTrue(updateRegistered.await(60, TimeUnit.SECONDS));

            UpdateSystemPolicyService updateSystemPolicyService = kernel.getContext().get(UpdateSystemPolicyService.class);
            assertThat("The UpdateSystemService should have one pending action.",
                    updateSystemPolicyService.getPendingActions(),
                    IsCollectionWithSize.hasSize(1));
            // Get the value of the pending Action
            String pendingAction = updateSystemPolicyService.getPendingActions().iterator().next();

            // GG_NEEDS_REVIEW: TODO : Call Fleet configuration service's cancel API when ready instead of calling IoT Jobs API
            IotJobsUtils.cancelJob(iotClient, result2.iotJobId());

            // Wait for indication that cancellation has gone through
            assertTrue(deploymentCancelled.await(240, TimeUnit.SECONDS));
            // the third deployment may have reached device.
            Set<String> pendingActions = updateSystemPolicyService.getPendingActions();
            if (pendingActions.size() == 1) {
                String newPendingAction = pendingActions.iterator().next();
                assertNotEquals(pendingAction, newPendingAction,
                        "The UpdateSystemService's one pending action should be be replaced.");
            } else if (pendingActions.size() > 1) {
                fail("Deployment not cancelled, pending actions: " + updateSystemPolicyService.getPendingActions());
            }

            // Now that we've verified that the job got cancelled, let's verify that the next job was picked up
            // and put into IN_PROGRESS state
            IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result3.iotJobId(), thingInfo.getThingName(),
                    Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

            // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
            assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
            assertThat(getCloudDeployedComponent("NonDisruptableService")::getState, eventuallyEval(is(State.RUNNING)));
            assertEquals("1.0.0",
                    getCloudDeployedComponent("NonDisruptableService").getConfig().find("version").getOnce());
        } finally {
            if (logListener != null) {
                Slf4jLogAdapter.removeGlobalListener(logListener);
            }
        }
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_updating_Component_WHEN_removing_field_from_recipe_THEN_kernel_config_remove_corresponding_field()
            throws Exception {
        // CustomerApp 0.9.1 has 'startup' key in lifecycle
        CreateDeploymentRequest createDeploymentRequest1 = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("0.9.1").build())).build();
        CreateDeploymentResponse result1 = draftAndCreateDeployment(createDeploymentRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result1.iotJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(10), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        GreengrassService customerApp = getCloudDeployedComponent("CustomerApp");
        assertNotNull(customerApp.getConfig().findTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC).getChild("startup"));

        // update with some local data
        customerApp.getRuntimeConfig().lookup("runtimeKey").withValue("val");

        // Second deployment to update CustomerApp, replace 'startup' key with 'run' key.
        CreateDeploymentRequest createDeploymentRequest2 = CreateDeploymentRequest.builder().components(
                Utils.immutableMap("CustomerApp",
                        ComponentDeploymentSpecification.builder().componentVersion("1.0.0").build())).build();
        CreateDeploymentResponse result2 = draftAndCreateDeployment(createDeploymentRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, result2.iotJobId(), thingInfo.getThingName(),
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
