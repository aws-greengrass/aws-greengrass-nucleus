/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.e2e.deployment;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicy;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.amazonaws.services.evergreen.model.ConfigurationValidationPolicy;
import com.amazonaws.services.evergreen.model.DeploymentPolicies;
import com.amazonaws.services.evergreen.model.FailureHandlingPolicy;
import com.amazonaws.services.evergreen.model.PackageMetaData;
import com.amazonaws.services.evergreen.model.PublishConfigurationResult;
import com.amazonaws.services.evergreen.model.SetConfigurationRequest;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionConflictException;
import com.aws.greengrass.dependency.State;
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
import com.aws.greengrass.testcommons.testutilities.GGExtension;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(GGExtension.class)
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
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("CustomerApp")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("SomeService").getState());
    }

    @Test
    void GIVEN_blank_kernel_WHEN_deployment_has_conflicts_THEN_job_should_fail_and_return_error(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseOfType(context, ComponentVersionConflictException.class);

        // New deployment contains dependency conflicts
        SetConfigurationRequest setRequest = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
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
                "com.aws.greengrass.componentmanager.exceptions.ComponentVersionConflictException"));
        assertThat(deploymentError, StringContains.containsString(getTestComponentNameInCloud("Mosquitto")));
        assertThat(deploymentError, StringContains.containsString(getTestComponentNameInCloud("SomeService") + "-v1.0.0==1.0.0"));
        assertThat(deploymentError, StringContains.containsString(getTestComponentNameInCloud("SomeOldService") + "-v0.9.0==0.9.0"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_deploy_fix_THEN_service_run_and_job_is_successful(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context, "Service "
                + getTestComponentNameInCloud("CustomerApp") + " in broken state after deployment");

        // Create first Job Doc with a faulty service (CustomerApp-0.9.0)
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withDeploymentPolicies(new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                        .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                .withAction(ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS).withTimeout(120))
                        .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING))
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        // Wait for deployment job to fail after three retries of starting CustomerApp
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(7), s -> s.equals(JobExecutionStatus.FAILED));
        // CustomerApp should be in BROKEN state
        assertEquals(State.BROKEN, getCloudDeployedComponent("CustomerApp").getState());

        // Create another job with a fix to the faulty service (CustomerApp-0.9.1).
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));
        // Ensure that main is FINISHED and CustomerApp is RUNNING.
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertEquals(State.RUNNING, getCloudDeployedComponent("CustomerApp").getState());
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_fails_due_to_service_broken_WHEN_failure_policy_is_rollback_THEN_deployment_is_rolled_back_and_job_fails(ExtensionContext context) throws Exception {
        ignoreExceptionUltimateCauseWithMessage(context, "Service "
                + getTestComponentNameInCloud("CustomerApp") + " in broken state after deployment");

        // Deploy some services that can be used for verification later
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("RedSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                .addPackagesEntry("YellowSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        // Create a Job Doc with a faulty service (CustomerApp-0.9.0) requesting rollback on failure
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withDeploymentPolicies(new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                        .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                .withAction(ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS).withTimeout(120))
                        .withFailureHandlingPolicy(FailureHandlingPolicy.ROLLBACK))
                .addPackagesEntry("RedSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                .addPackagesEntry("YellowSignal", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"))
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.0"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);

        String jobId2 = publishResult2.getJobId();
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, jobId2, thingInfo.getThingName(),
                Duration.ofMinutes(5), s -> s.equals(JobExecutionStatus.FAILED));

        // Main should be INSTALLED state and CustomerApp should be stopped and removed
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("RedSignal")::getState, eventuallyEval(is(State.FINISHED),
                Duration.ofSeconds(10L)));
        assertThat(getCloudDeployedComponent("YellowSignal")::getState, eventuallyEval(is(State.FINISHED)));
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("CustomerApp").getState());
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("Mosquitto").getState());
        assertThrows(ServiceLoadException.class, () -> getCloudDeployedComponent("GreenSignal").getState());

        // IoT Job should have failed with correct message.
        assertEquals(DeploymentResult.DeploymentStatus.FAILED_ROLLBACK_COMPLETE.name(), iotClient
                .describeJobExecution(DescribeJobExecutionRequest.builder().jobId(jobId2).thingName(thingInfo.getThingName())
                        .build()).execution().statusDetails().detailsMap().get("detailed-deployment-status"));
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_some_running_services_WHEN_cancel_event_received_and_kernel_is_waiting_for_safe_time_THEN_deployment_should_be_canceled() throws Exception {
        // First Deployment to have a service running in Kernel which has a safety check that always returns
        // false, i.e. keeps waiting forever
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
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
                } catch (LifecycleIPCException e) { }
            }
        });

        // Second deployment to update the service which is currently running an important task so deployment should
        // wait for a safe time to update
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withDeploymentPolicies(new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                        .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                .withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS)
                                .withTimeout(120)
                        ))
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion(
                        "1.0.1"));
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

        // TODO : Call Fleet configuration service's cancel API when ready instead of calling IoT Jobs API
        IotJobsUtils.cancelJob(iotClient, publishResult2.getJobId());

        // Wait for indication that cancellation has gone through
        assertTrue(deploymentCancelled.await(60, TimeUnit.SECONDS));
        assertFalse(kernel.getContext().get(UpdateSystemSafelyService.class)
                .hasPendingUpdateAction(publishResult2.getConfigurationArn()));

        // Ensure that main is finished, which is its terminal state, so this means that all updates ought to be done
        assertThat(kernel.getMain()::getState, eventuallyEval(is(State.FINISHED)));
        assertThat(getCloudDeployedComponent("NonDisruptableService")::getState, eventuallyEval(is(State.RUNNING)));
        assertEquals("1.0.0", getCloudDeployedComponent("NonDisruptableService").getConfig()
                .find("version").getOnce());

        Slf4jLogAdapter.removeGlobalListener(logListener);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_received_WHEN_skip_safety_check_THEN_safety_check_skipped() throws Exception {
        // GIVEN
        // First Deployment to have a service running in Kernel which has a safety check that always returns
        // false, i.e. keeps waiting forever
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
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
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withDeploymentPolicies(new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                        .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                .withAction(ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS).withTimeout(120)))
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion(
                        "1.0.1"));
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
        assertEquals("1.0.1", getCloudDeployedComponent("NonDisruptableService").getConfig()
                .find("version").getOnce());
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_deployment_in_progress_with_more_jobs_queued_in_cloud_WHEN_cancel_event_received_and_kernel_is_waiting_for_safe_time_THEN_deployment_should_be_canceled() throws Exception {
        // First Deployment to have a service running in Kernel which has a safety check that always returns
        // false, i.e. keeps waiting forever
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
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
                } catch (LifecycleIPCException e) { }
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
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withDeploymentPolicies(new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                        .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                .withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS).withTimeout(120)))
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion(
                        "1.0.1"));
        PublishConfigurationResult publishResult2 = setAndPublishFleetConfiguration(setRequest2);
        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult2.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(3), s -> s.equals(JobExecutionStatus.IN_PROGRESS));

        // Create one more deployment so that it's queued in cloud
        SetConfigurationRequest setRequest3 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .withDeploymentPolicies(new DeploymentPolicies()
                        .withConfigurationValidationPolicy(new ConfigurationValidationPolicy().withTimeout(120))
                        .withFailureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)
                        .withComponentUpdatePolicy(new ComponentUpdatePolicy()
                                .withAction(ComponentUpdatePolicyAction.NOTIFY_COMPONENTS).withTimeout(120)))
                .addPackagesEntry("NonDisruptableService", new PackageMetaData().withRootComponent(true).withVersion(
                        "1.0.1"));
        PublishConfigurationResult publishResult3 = setAndPublishFleetConfiguration(setRequest3);

        // Wait for the second deployment to start waiting for safe time to update and
        // then cancel it's corresponding job from cloud
        assertTrue(updateRegistered.await(60, TimeUnit.SECONDS));
        assertTrue(kernel.getContext().get(UpdateSystemSafelyService.class)
                .hasPendingUpdateAction(publishResult2.getConfigurationArn()));

        // TODO : Call Fleet configuration service's cancel API when ready instead of calling IoT Jobs API
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
        assertEquals("1.0.0", getCloudDeployedComponent("NonDisruptableService").getConfig()
                .find("version").getOnce());

        Slf4jLogAdapter.removeGlobalListener(logListener);
    }

    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    @Test
    void GIVEN_updating_Component_WHEN_removing_field_from_recipe_THEN_kernel_config_remove_corresponding_field() throws Exception {
        // CustomerApp 0.9.1 has 'startup' key in lifecycle
        SetConfigurationRequest setRequest1 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("0.9.1"));
        PublishConfigurationResult publishResult1 = setAndPublishFleetConfiguration(setRequest1);

        IotJobsUtils.waitForJobExecutionStatusToSatisfy(iotClient, publishResult1.getJobId(), thingInfo.getThingName(),
                Duration.ofMinutes(10), s -> s.equals(JobExecutionStatus.SUCCEEDED));

        GreengrassService customerApp = getCloudDeployedComponent("CustomerApp");
        assertNotNull(customerApp.getConfig().findTopics(SERVICE_LIFECYCLE_NAMESPACE_TOPIC).getChild("startup"));

        // update with some local data
        customerApp.getRuntimeConfig().lookup("runtimeKey").withValue("val");

        // Second deployment to update CustomerApp, replace 'startup' key with 'run' key.
        SetConfigurationRequest setRequest2 = new SetConfigurationRequest()
                .withTargetName(thingGroupName)
                .withTargetType(THING_GROUP_TARGET_TYPE)
                .addPackagesEntry("CustomerApp", new PackageMetaData().withRootComponent(true).withVersion("1.0.0"));
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
