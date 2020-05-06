/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeviceConfiguration;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentType.IOT_JOBS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class IotJobsHelperTest {

    private static final String TEST_THING_NAME = "TEST_THING";
    private static final String TEST_JOB_ID = "TestJobId";
    private static final String REJECTION_MESSAGE = "Job update rejected";
    private static final String TEST_CERT_PATH = "TestCertPath";
    private static final String TEST_PRIVATE_KEY_PATH = "TestPrivateKeyPath";
    private static final String TEST_ROOT_CA_PATH = "TestRootCaPath";
    private static final String TEST_MQTT_CLIENT_ENDPOINT = "TestMqttClientEndpoint";

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

    @Mock
    DeviceConfigurationHelper deviceConfigurationHelper;

    @Mock
    IotJobsHelper.AWSIotMqttConnectionFactory awsIotMqttConnectionFactory;

    @Mock
    IotJobsHelper.IotJobsClientFactory mockIotJobsClientFactory;

    @Mock
    LinkedBlockingQueue<Deployment> mockDeploymentsQueue;

    @Mock
    MqttClientConnectionEvents mockCallbacks;

    @Captor
    ArgumentCaptor<Consumer<RejectedError>> rejectedErrorCaptor;

    @Captor
    ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> updateJobExecutionResponseCaptor;

    @Captor
    ArgumentCaptor<Consumer<DescribeJobExecutionResponse>> describeJobResponseCaptor;

    @Captor
    ArgumentCaptor<Consumer<JobExecutionsChangedEvent>> eventChangeResponseCaptor;


    private IotJobsHelper iotJobsHelper;

    @BeforeEach
    public void setup() throws Exception {
        when(deviceConfigurationHelper.getDeviceConfiguration()).thenReturn(getDeviceConfiguration());
        iotJobsHelper = new IotJobsHelper(deviceConfigurationHelper, awsIotMqttConnectionFactory,
                mockIotJobsClientFactory, mockDeploymentsQueue, mockCallbacks);
        when(awsIotMqttConnectionFactory.getAwsIotMqttConnection(any(), any())).thenReturn(mockMqttClientConnection);
        when(mockIotJobsClientFactory.getIotJobsClient(eq(mockMqttClientConnection))).thenReturn(mockIotJobsClient);
        CompletableFuture<Boolean> completableFuture = new CompletableFuture<>();
        completableFuture.complete(Boolean.TRUE);
        CompletableFuture<Integer> integercompletableFuture = new CompletableFuture<>();
        integercompletableFuture.complete(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any(), any(), any()))
                .thenReturn(integercompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any(), any(), any()))
                .thenReturn(integercompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any(), any(), any()))
                .thenReturn(integercompletableFuture);
        when(mockMqttClientConnection.connect()).thenReturn(completableFuture);
        iotJobsHelper.connect();
     }

    @Test
    public void GIVEN_device_configured_WHEN_connecting_to_iot_cloud_THEN_connection_successful() throws Exception {
        verify(awsIotMqttConnectionFactory).getAwsIotMqttConnection(eq(getDeviceConfiguration()), eq(mockCallbacks));
        verify(mockMqttClientConnection).connect();
        verify(mockIotJobsClient).SubscribeToJobExecutionsChangedEvents(any(), any(), any());
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionAccepted(any(), any(), any());
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionRejected(any(), any(), any());
    }

    @Test
    public void GIVEN_connected_to_iot_WHEN_subscribe_to_jobs_topics_THEN_get_notification_for_queued_jobs()
            throws Exception {
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToJobExecutionsChangedEvents(any(), eq(
                QualityOfService.AT_LEAST_ONCE), eventChangeResponseCaptor.capture());
        JobExecutionsChangedEvent event = new JobExecutionsChangedEvent();
        HashMap<JobStatus, List<JobExecutionSummary>> jobs = new HashMap<>();
        jobs.put(JobStatus.QUEUED, Arrays.asList(new JobExecutionSummary()));
        event.jobs = jobs;
        eventChangeResponseCaptor.getValue().accept(event);
        verify(mockIotJobsClient, times(3)).PublishDescribeJobExecution(any(),
                eq(QualityOfService.AT_LEAST_ONCE));
    }

    @Test
    public void GIVEN_connected_to_iot_WHEN_subscribe_to_jobs_topics_THEN_get_notification_for_in_progress_jobs()
            throws Exception {
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToJobExecutionsChangedEvents(any(), eq(
                QualityOfService.AT_LEAST_ONCE), eventChangeResponseCaptor.capture());
        JobExecutionsChangedEvent event = new JobExecutionsChangedEvent();
        HashMap<JobStatus, List<JobExecutionSummary>> jobs = new HashMap<>();
        jobs.put(JobStatus.IN_PROGRESS, Arrays.asList(new JobExecutionSummary()));
        event.jobs = jobs;
        eventChangeResponseCaptor.getValue().accept(event);
        verify(mockIotJobsClient, times(2)).PublishDescribeJobExecution(any(),
                eq(QualityOfService.AT_LEAST_ONCE));
    }

    @Test
    public void GIVEN_connected_to_iot_WHEN_subscribe_to_jobs_topics_THEN_get_job_description()
            throws Exception {
        LinkedBlockingQueue<Deployment> mockDeploymentsQueue = mock(LinkedBlockingQueue.class);
        iotJobsHelper.setDeploymentsQueue(mockDeploymentsQueue);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionAccepted(any(), eq(
                QualityOfService.AT_LEAST_ONCE), describeJobResponseCaptor.capture());
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionRejected(any(), eq(
                QualityOfService.AT_LEAST_ONCE), rejectedErrorCaptor.capture());
        JobExecutionData jobExecutionData = new JobExecutionData();
        jobExecutionData.jobId = TEST_JOB_ID;
        jobExecutionData.status = JobStatus.QUEUED;
        HashMap<String, Object> sampleJobDocument = new HashMap<>();
        sampleJobDocument.put("DeploymentId", TEST_JOB_ID);
        jobExecutionData.jobDocument = sampleJobDocument;
        DescribeJobExecutionResponse describeJobExecutionResponse = new DescribeJobExecutionResponse();
        describeJobExecutionResponse.execution = jobExecutionData;
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse);
        ArgumentCaptor<Deployment> deploymentArgumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(mockDeploymentsQueue).offer(deploymentArgumentCaptor.capture());
        Deployment actualDeployment = deploymentArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID, actualDeployment.getId());
        assertEquals(IOT_JOBS, actualDeployment.getDeploymentType());
        assertEquals("{\"DeploymentId\":\"TestJobId\"}", actualDeployment.getDeploymentDocument());
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
         assertEquals(TEST_THING_NAME, actualRequest.thingName);
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
        assertEquals(TEST_THING_NAME, actualRequest.thingName);
        assertEquals("$next", actualRequest.jobId);
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionRejected(requestArgumentCaptor.capture(), eq(
                QualityOfService.AT_LEAST_ONCE), eq(rejectedErrorConsumer));
        actualRequest = requestArgumentCaptor.getValue();
        assertEquals(TEST_THING_NAME, actualRequest.thingName);
        assertEquals("$next", actualRequest.jobId);
    }

    @Test
    public void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_request_next_pending_jobDoc() {

        iotJobsHelper.requestNextPendingJobDocument();
        ArgumentCaptor<DescribeJobExecutionRequest> requestArgumentCaptor =
                ArgumentCaptor.forClass(DescribeJobExecutionRequest.class);
        verify(mockIotJobsClient, times(2)).PublishDescribeJobExecution(requestArgumentCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        DescribeJobExecutionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals("$next",actualRequest.jobId);
        assertEquals(TEST_THING_NAME, actualRequest.thingName);
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
        assertEquals(TEST_THING_NAME, actualRequest.thingName);

        verify(mockMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace(
                "{thingName}", TEST_THING_NAME).replace("{jobId}", TEST_JOB_ID)));

        ArgumentCaptor<UpdateJobExecutionRequest> publishRequestCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionRequest.class);
        verify(mockIotJobsClient).PublishUpdateJobExecution(publishRequestCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        UpdateJobExecutionRequest publishRequest = publishRequestCaptor.getValue();
        assertEquals(TEST_JOB_ID, publishRequest.jobId);
        assertEquals(JobStatus.IN_PROGRESS, publishRequest.status);
        assertEquals(statusDetails, publishRequest.statusDetails);
        assertEquals(TEST_THING_NAME, publishRequest.thingName);
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
        assertEquals(TEST_THING_NAME, actualRequest.thingName);

        verify(mockMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_REJECTED_TOPIC.replace(
                "{thingName}", TEST_THING_NAME).replace("{jobId}", TEST_JOB_ID)));

        ArgumentCaptor<UpdateJobExecutionRequest> publishRequestCaptor =
                ArgumentCaptor.forClass(UpdateJobExecutionRequest.class);
        verify(mockIotJobsClient).PublishUpdateJobExecution(publishRequestCaptor.capture(),
                eq(QualityOfService.AT_LEAST_ONCE));
        UpdateJobExecutionRequest publishRequest = publishRequestCaptor.getValue();
        assertEquals(TEST_JOB_ID, publishRequest.jobId);
        assertEquals(JobStatus.IN_PROGRESS, publishRequest.status);
        assertEquals(statusDetails, publishRequest.statusDetails);
        assertEquals(TEST_THING_NAME, publishRequest.thingName);
    }

    private DeviceConfiguration getDeviceConfiguration() {
        return new DeviceConfiguration(TEST_THING_NAME, TEST_CERT_PATH,
                TEST_PRIVATE_KEY_PATH, TEST_ROOT_CA_PATH, TEST_MQTT_CLIENT_ENDPOINT);
    }
}
