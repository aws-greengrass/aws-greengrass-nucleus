/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class IotJobsHelperTest {

    private static final String THING_NAME = "TEST_THING";
    private static final String TEST_JOB_ID = "TestJobId";

    @Mock
    private IotJobsClient mockIotJobsClient;

    @Mock
    private MqttClientConnection mockMqttClientConnection;

    @Mock
    Consumer<JobExecutionsChangedEvent> eventConsumer;

    @Mock
    Consumer<DescribeJobExecutionResponse> describeJobConsumer;

    @Mock
    Consumer<RejectedError> rejectedErrorConsumer;

    @Captor
    ArgumentCaptor<Consumer<RejectedError>> rejectedErrorCaptor;

    @Captor
    ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> updateJobExecutionResponseCaptor;


    private IotJobsHelper iotJobsHelper;

    @BeforeEach
    public void setup() {
        iotJobsHelper = new IotJobsHelper(THING_NAME, mockMqttClientConnection, mockIotJobsClient);
     }

     @Test
     public void GIVEN_JobsClientAndMqttConnection_WHEN_MqttConnected_THEN_SubscribeToEventNotifications()
             throws ExecutionException, InterruptedException {
         CompletableFuture<Integer> integerCompletableFuture = mock(CompletableFuture.class);
            when(integerCompletableFuture.get()).thenReturn(1);
         when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any(JobExecutionsChangedSubscriptionRequest.class)
                 , eq(QualityOfService.AT_LEAST_ONCE), eq(eventConsumer))).thenReturn(integerCompletableFuture);
        iotJobsHelper.subscribeToEventNotifications(eventConsumer);
         ArgumentCaptor<JobExecutionsChangedSubscriptionRequest> requestArgumentCaptor =
                 ArgumentCaptor.forClass(JobExecutionsChangedSubscriptionRequest.class);
        verify(mockIotJobsClient).SubscribeToJobExecutionsChangedEvents(requestArgumentCaptor.capture(), eq(
                QualityOfService.AT_LEAST_ONCE), eq(eventConsumer));
         JobExecutionsChangedSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
         assertEquals(THING_NAME, actualRequest.thingName);
     }

    @Test
    public void GIVEN_JobsClientAndMqttConnection_WHEN_MqttConnected_THEN_SubscribeToGetNextJobDescription()
            throws ExecutionException, InterruptedException {
        CompletableFuture<Integer> integerCompletableFuture = mock(CompletableFuture.class);
        when(integerCompletableFuture.get()).thenReturn(1);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(
                any(DescribeJobExecutionSubscriptionRequest.class),
                eq(QualityOfService.AT_LEAST_ONCE), eq(describeJobConsumer))).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(
                any(DescribeJobExecutionSubscriptionRequest.class),
                eq(QualityOfService.AT_LEAST_ONCE), eq(rejectedErrorConsumer))).thenReturn(integerCompletableFuture);

        iotJobsHelper.subscribeToGetNextJobDecription(describeJobConsumer, rejectedErrorConsumer);
        ArgumentCaptor<DescribeJobExecutionSubscriptionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(DescribeJobExecutionSubscriptionRequest.class);
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionAccepted(requestArgumentCaptor.capture(), eq(
                QualityOfService.AT_LEAST_ONCE), eq(describeJobConsumer));
        DescribeJobExecutionSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals(THING_NAME, actualRequest.thingName);
        assertEquals("$next", actualRequest.jobId);
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionRejected(requestArgumentCaptor.capture(), eq(
                QualityOfService.AT_LEAST_ONCE), eq(rejectedErrorConsumer));
        actualRequest = requestArgumentCaptor.getValue();
        assertEquals(THING_NAME, actualRequest.thingName);
        assertEquals("$next", actualRequest.jobId);
    }

    @Test
    public void GIVEN_JobsClientAndMqttConnection_WHEN_MqttConnected_THEN_RequestNextPendingJobDoc() {

        iotJobsHelper.requestNextPendingJobDocument();
        ArgumentCaptor<DescribeJobExecutionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(DescribeJobExecutionRequest.class);
        verify(mockIotJobsClient).PublishDescribeJobExecution(requestArgumentCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        DescribeJobExecutionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals("$next",actualRequest.jobId);
        assertEquals(THING_NAME, actualRequest.thingName);
        assertTrue(actualRequest.includeJobDocument);
    }

    @Test
    public void GIVEN_JobsClientAndMqttConnection_WHEN_MqttConnected_THEN_UpdateJobStatusAccepted() {
        HashMap<String, String> statusDetails = new HashMap<>();
        statusDetails.put("type", "test" );
        iotJobsHelper.updateJobStatus(TEST_JOB_ID, JobStatus.IN_PROGRESS, statusDetails);
        ArgumentCaptor<UpdateJobExecutionSubscriptionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionSubscriptionRequest.class);
        verify(mockIotJobsClient).SubscribeToUpdateJobExecutionAccepted(requestArgumentCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE), updateJobExecutionResponseCaptor.capture());

        UpdateJobExecutionSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID,actualRequest.jobId);
        assertEquals(THING_NAME, actualRequest.thingName);

        Consumer<UpdateJobExecutionResponse> jobResponseConsumer = updateJobExecutionResponseCaptor.getValue();
        UpdateJobExecutionResponse mockJobExecutionResponse = mock(UpdateJobExecutionResponse.class);
        jobResponseConsumer.accept(mockJobExecutionResponse);
        verify(mockMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace(
                "{thingName}", THING_NAME).replace("{jobId}", TEST_JOB_ID)));

        ArgumentCaptor<UpdateJobExecutionRequest> publishRequestCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionRequest.class);
        verify(mockIotJobsClient).PublishUpdateJobExecution(publishRequestCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        UpdateJobExecutionRequest publishRequest = publishRequestCaptor.getValue();
        assertEquals(TEST_JOB_ID, publishRequest.jobId);
        assertEquals(JobStatus.IN_PROGRESS, publishRequest.status);
        assertEquals(statusDetails, publishRequest.statusDetails);
        assertEquals(THING_NAME, publishRequest.thingName);
    }

    @Test
    public void GIVEN_JobsClientAndMqttConnection_WHEN_MqttConnected_THEN_UpdateJobStatusRejected() {
        HashMap<String, String> statusDetails = new HashMap<>();
        statusDetails.put("type", "test" );
        iotJobsHelper.updateJobStatus(TEST_JOB_ID, JobStatus.IN_PROGRESS, statusDetails);
        ArgumentCaptor<UpdateJobExecutionSubscriptionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionSubscriptionRequest.class);
        verify(mockIotJobsClient).SubscribeToUpdateJobExecutionRejected(requestArgumentCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE), rejectedErrorCaptor.capture());

        UpdateJobExecutionSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID,actualRequest.jobId);
        assertEquals(THING_NAME, actualRequest.thingName);

        Consumer<RejectedError> rejectedErrorConsumer = rejectedErrorCaptor.getValue();
        RejectedError mockRejectError = mock(RejectedError.class);
        rejectedErrorConsumer.accept(mockRejectError);
        verify(mockMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_REJECTED_TOPIC.replace(
                "{thingName}", THING_NAME).replace("{jobId}", TEST_JOB_ID)));

        ArgumentCaptor<UpdateJobExecutionRequest> publishRequestCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionRequest.class);
        verify(mockIotJobsClient).PublishUpdateJobExecution(publishRequestCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        UpdateJobExecutionRequest publishRequest = publishRequestCaptor.getValue();
        assertEquals(TEST_JOB_ID, publishRequest.jobId);
        assertEquals(JobStatus.IN_PROGRESS, publishRequest.status);
        assertEquals(statusDetails, publishRequest.statusDetails);
        assertEquals(THING_NAME, publishRequest.thingName);
    }


}
