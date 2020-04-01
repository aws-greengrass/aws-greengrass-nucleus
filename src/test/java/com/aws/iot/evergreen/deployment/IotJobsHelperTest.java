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
    private static final Long TEST_JOB_EXECUTION_NUMBER = 1234L;
    private static final String REJECTION_MESSAGE = "Job update rejected";

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
    ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> jobUpdateAcceptedHandler;

    @Captor
    ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> updateJobExecutionResponseCaptor;


    private IotJobsHelper iotJobsHelper;

    @BeforeEach
    public void setup() {
        iotJobsHelper = new IotJobsHelper(THING_NAME, mockMqttClientConnection, mockIotJobsClient);
     }

     @Test
     public void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_subscribe_to_eventNotifications()
             throws Exception {
         CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
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
    public void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_subscribe_to_getNextJobDescription()
            throws Exception {
        CompletableFuture<Integer> integerCompletableFuture = new CompletableFuture<>();
        integerCompletableFuture.complete(1);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(
                any(DescribeJobExecutionSubscriptionRequest.class),
                eq(QualityOfService.AT_LEAST_ONCE), eq(describeJobConsumer))).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(
                any(DescribeJobExecutionSubscriptionRequest.class),
                eq(QualityOfService.AT_LEAST_ONCE), eq(rejectedErrorConsumer))).thenReturn(integerCompletableFuture);

        iotJobsHelper.subscribeToGetNextJobDescription(describeJobConsumer, rejectedErrorConsumer);
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
    public void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_request_next_pending_jobDoc() {

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
    public void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_update_jobStatus_successfully()
    throws Exception {
        HashMap<String, String> statusDetails = new HashMap<>();
        statusDetails.put("type", "test" );
        CompletableFuture cf = new CompletableFuture();
        cf.complete(null);
        ArgumentCaptor<UpdateJobExecutionSubscriptionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionSubscriptionRequest.class);
        when(mockIotJobsClient.PublishUpdateJobExecution(any(), any())).thenAnswer(invocationOnMock -> {
            verify(mockIotJobsClient).SubscribeToUpdateJobExecutionAccepted(requestArgumentCaptor.capture(),
                    eq(QualityOfService.AT_LEAST_ONCE), updateJobExecutionResponseCaptor.capture());
            Consumer<UpdateJobExecutionResponse> jobResponseConsumer = updateJobExecutionResponseCaptor.getValue();
            UpdateJobExecutionResponse mockJobExecutionResponse = mock(UpdateJobExecutionResponse.class);
            jobResponseConsumer.accept(mockJobExecutionResponse);
            return cf;
        });
        iotJobsHelper.updateJobStatus(TEST_JOB_ID, JobStatus.IN_PROGRESS, statusDetails);
        verify(mockIotJobsClient).SubscribeToUpdateJobExecutionAccepted(requestArgumentCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE), updateJobExecutionResponseCaptor.capture());

        UpdateJobExecutionSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID,actualRequest.jobId);
        assertEquals(THING_NAME, actualRequest.thingName);

        verify(mockMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace(
                "{thingName}", THING_NAME).replace("{jobId}", TEST_JOB_ID)));

        ArgumentCaptor<UpdateJobExecutionRequest> publishRequestCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionRequest.class);
        verify(mockIotJobsClient).PublishUpdateJobExecution(publishRequestCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        UpdateJobExecutionRequest publishRequest = publishRequestCaptor.getValue();
        assertEquals(TEST_JOB_ID, publishRequest.jobId);
        assertEquals(TEST_JOB_EXECUTION_NUMBER, publishRequest.executionNumber);
        assertEquals(JobStatus.IN_PROGRESS, publishRequest.status);
        assertEquals(statusDetails, publishRequest.statusDetails);
        assertEquals(THING_NAME, publishRequest.thingName);
    }

    @Test
    public void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_update_jobStatus_failed()
            throws Exception {
        CompletableFuture cf = new CompletableFuture();
        cf.complete(null);
        ArgumentCaptor<UpdateJobExecutionSubscriptionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionSubscriptionRequest.class);
        when(mockIotJobsClient.PublishUpdateJobExecution(any(), any())).thenAnswer(invocationOnMock -> {
            verify(mockIotJobsClient).SubscribeToUpdateJobExecutionRejected(requestArgumentCaptor.capture(),
                    eq(QualityOfService.AT_LEAST_ONCE), rejectedErrorCaptor.capture());
            Consumer<RejectedError> rejectedErrorConsumer = rejectedErrorCaptor.getValue();
            RejectedError mockRejectError = new RejectedError();
            mockRejectError.message = REJECTION_MESSAGE;
            rejectedErrorConsumer.accept(mockRejectError);
            return cf;
        });
        HashMap<String, String> statusDetails = new HashMap<>();
        statusDetails.put("type", "test" );
        try {
            iotJobsHelper.updateJobStatus(TEST_JOB_ID, JobStatus.IN_PROGRESS, statusDetails);
        } catch (ExecutionException e) {
            //verify that exception is thrown with the expected message
            assertTrue(e.getCause().getMessage().equals(REJECTION_MESSAGE));
        }

        UpdateJobExecutionSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID,actualRequest.jobId);
        assertEquals(THING_NAME, actualRequest.thingName);

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
