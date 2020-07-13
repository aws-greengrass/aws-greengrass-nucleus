/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.e2e.util;

import com.amazonaws.arn.Arn;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.DeleteRoleRequest;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AddThingToThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CancelJobRequest;
import software.amazon.awssdk.services.iot.model.CreateJobRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupRequest;
import software.amazon.awssdk.services.iot.model.CreateThingGroupResponse;
import software.amazon.awssdk.services.iot.model.DeleteJobRequest;
import software.amazon.awssdk.services.iot.model.DeletePolicyRequest;
import software.amazon.awssdk.services.iot.model.DeleteRoleAliasRequest;
import software.amazon.awssdk.services.iot.model.DeleteThingGroupRequest;
import software.amazon.awssdk.services.iot.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.InvalidRequestException;
import software.amazon.awssdk.services.iot.model.JobExecutionStatus;
import software.amazon.awssdk.services.iot.model.ResourceNotFoundException;
import software.amazon.awssdk.services.iot.model.TargetSelection;
import software.amazon.awssdk.services.iot.model.TimeoutConfig;


import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import static com.aws.iot.evergreen.easysetup.DeviceProvisioningHelper.ThingInfo;

public final class IotJobsUtils {

    private IotJobsUtils() {
    }

    public static void createJobWithId(IotClient client, String document, String jobId, String... targets) {
        client.createJob(
                CreateJobRequest.builder().jobId(jobId).targets(targets).targetSelection(TargetSelection.SNAPSHOT)
                        .document(document).description("E2E Test: " + new Date())
                        .timeoutConfig(TimeoutConfig.builder().inProgressTimeoutInMinutes(10L).build()).build());
    }

    public static void waitForJobExecutionStatusToSatisfy(IotClient client, String jobId, String thingName,
                                                          Duration timeout, Predicate<JobExecutionStatus> condition)
            throws TimeoutException {
        Instant deadline = Instant.now().plusMillis(timeout.toMillis());

        JobExecutionStatus status = null;
        ResourceNotFoundException lastException = null;
        while (deadline.isAfter(Instant.now())) {
            try {
                status = client.describeJobExecution(DescribeJobExecutionRequest.builder()
                        .jobId(jobId).thingName(thingName).build()).execution().status();
                if (condition.test(status)) {
                    return;
                }
            } catch (ResourceNotFoundException e) {
                lastException = e;
            }
            // Wait a little bit before checking again
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }
        throw new TimeoutException(status == null && lastException != null ? lastException.getMessage() :
                "Job execution status is " + status);
    }

    public static CreateThingGroupResponse createThingGroupAndAddThing(IotClient client, ThingInfo thingInfo) {
        String thingGroupName = "e2etestgroup-" + UUID.randomUUID().toString();
        CreateThingGroupResponse response =
                client.createThingGroup(CreateThingGroupRequest.builder().thingGroupName(thingGroupName).build());

        client.addThingToThingGroup(AddThingToThingGroupRequest.builder().thingArn(thingInfo.getThingArn())
                .thingGroupArn(response.thingGroupArn()).build());

        return response;
    }

    public static void cleanThingGroup(IotClient client, String thingGroupName) {
        client.deleteThingGroup(DeleteThingGroupRequest.builder().thingGroupName(thingGroupName).build());
    }

    public static void cleanJob(IotClient client, String jobId) {
        cancelJob(client, jobId);
        client.deleteJob(DeleteJobRequest.builder().jobId(jobId).force(true).build());
    }

    public static void cancelJob(IotClient client, String jobId) {
        try {
            client.cancelJob(CancelJobRequest.builder().jobId(jobId).force(true).build());
        } catch (InvalidRequestException e) {
            // Ignore can't cancel due to job already completed or canceled
            if (!e.getMessage().contains("in status COMPLETED cannot") && !e.getMessage()
                    .contains("in status CANCELED cannot be")) {
                throw e;
            }
        }
    }

    /**
     * Mock configuration ARN which should only be used in E2E tests not using Fleet Configuration Service.
     *
     * @param resourceIdVersion Configuration resource ID and version, in format "thing/thing-name:1"
     * @return String formatted ARN
     */
    public static String generateMockConfigurationArn(String resourceIdVersion) {
        return Arn.builder().withPartition("aws").withAccountId("1234567890").withRegion("test-region").withService(
                "gg").withResource(String.format("configuration:%s", resourceIdVersion)).build().toString();
    }

    /**
     * Clean Up IoT/IAM roles for using TES.
     *
     * @param roleName IAM role Name
     * @param roleAliasName IOT roleAlias name
     * @param certArn IOT certificate Arn
     */
    public static void cleanUpIotRoleForTest(IotClient iotClient, IamClient iamClient, String roleName, String roleAliasName, String certArn) {
        try {
            DeleteRoleAliasRequest deleteRoleAliasRequest = DeleteRoleAliasRequest.builder()
                    .roleAlias(roleAliasName).build();
            iotClient.deleteRoleAlias(deleteRoleAliasRequest);
        } catch (ResourceNotFoundException e) {
            // Ignore as role alias does not exist
        }
        try {
            DeleteRoleRequest deleteRoleRequest = DeleteRoleRequest.builder()
                    .roleName(roleName).build();
            iamClient.deleteRole(deleteRoleRequest);
        } catch (ResourceNotFoundException e) {
            // Ignore as role alias does not exist
        }
        String iotRolePolicyName = "EvergreenTESCertificatePolicy" + roleAliasName;
        try {
            DetachPolicyRequest detachPolicyRequest = DetachPolicyRequest.builder()
                    .policyName(iotRolePolicyName).target(certArn).build();
            iotClient.detachPolicy(detachPolicyRequest);
            DeletePolicyRequest deletePolicyRequest = DeletePolicyRequest.builder()
                    .policyName(iotRolePolicyName).build();
            iotClient.deletePolicy(deletePolicyRequest);
        } catch (ResourceNotFoundException e) {
            // Ignore as policy does not exist
        }
    }
}
