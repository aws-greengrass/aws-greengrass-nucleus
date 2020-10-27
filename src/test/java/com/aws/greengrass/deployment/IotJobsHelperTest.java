/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentTaskMetadata;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.TestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.Timestamp;
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
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.IOT_JOBS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.LOCAL;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class IotJobsHelperTest {

    private static final String TEST_THING_NAME = "TEST_THING";
    private static final String REJECTION_MESSAGE = "Job update rejected";

    @Mock
    Consumer<JobExecutionsChangedEvent> eventConsumer;
    @Mock
    Consumer<DescribeJobExecutionResponse> describeJobConsumer;
    @Mock
    Consumer<RejectedError> rejectedErrorConsumer;
    @Mock
    DeviceConfiguration deviceConfiguration;
    @Mock
    IotJobsHelper.IotJobsClientFactory mockIotJobsClientFactory;
    @Mock
    DeploymentQueue mockDeploymentQueue;
    @Mock
    DeploymentStatusKeeper deploymentStatusKeeper;
    @Mock
    Kernel mockKernel;
    @Mock
    DeploymentService mockDeploymentService;
    @Mock
    WrapperMqttClientConnection mockWrapperMqttClientConnection;
    @Mock
    IotJobsHelper.WrapperMqttConnectionFactory mockWrapperMqttConnectionFactory;
    @Mock
    private IotJobsClient mockIotJobsClient;
    @Mock
    MqttClient mockMqttClient;

    @Captor
    ArgumentCaptor<Consumer<RejectedError>> rejectedErrorCaptor;
    @Captor
    ArgumentCaptor<Consumer<UpdateJobExecutionResponse>> updateJobExecutionResponseCaptor;
    @Captor
    ArgumentCaptor<Consumer<DescribeJobExecutionResponse>> describeJobResponseCaptor;
    @Captor
    ArgumentCaptor<Consumer<JobExecutionsChangedEvent>> eventChangeResponseCaptor;

    private final ExecutorService executorService = TestUtils.synchronousExecutorService();
    private IotJobsHelper iotJobsHelper;

    @BeforeEach
    void setup() throws Exception {
        iotJobsHelper = new IotJobsHelper(deviceConfiguration, mockIotJobsClientFactory, mockDeploymentQueue,
                deploymentStatusKeeper, executorService,mockKernel, mockWrapperMqttConnectionFactory, mockMqttClient);
        Topic mockThingNameTopic = mock(Topic.class);
        when(mockThingNameTopic.getOnce()).thenReturn(TEST_THING_NAME);
        when(deviceConfiguration.getThingName()).thenReturn(mockThingNameTopic);
        when(mockIotJobsClientFactory.getIotJobsClient(any())).thenReturn(mockIotJobsClient);
        when(mockWrapperMqttConnectionFactory.getAwsIotMqttConnection(any()))
                .thenReturn(mockWrapperMqttClientConnection);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any(), any(), any()))
                .thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any(), any(), any()))
                .thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any(), any(), any()))
                .thenReturn(integerCompletableFuture);

        lenient().when(mockKernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS)).thenReturn(mockDeploymentService);
        iotJobsHelper.postInject();
     }

    @Test
    void GIVEN_device_configured_WHEN_connecting_to_iot_cloud_THEN_connection_successful() throws Exception {
        verify(mockMqttClient).addToCallbackEvents(any());
        verify(mockIotJobsClient).SubscribeToJobExecutionsChangedEvents(any(), any(), any());
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionAccepted(any(), any(), any());
        verify(mockIotJobsClient).SubscribeToDescribeJobExecutionRejected(any(), any(), any());
    }

    @Test
    void GIVEN_connected_to_iot_WHEN_subscribe_to_jobs_topics_THEN_get_notification_for_queued_jobs()
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
    void GIVEN_connected_to_iot_WHEN_subscribe_to_jobs_topics_THEN_get_notification_for_in_progress_jobs()
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
    void GIVEN_not_connected_to_iot_WHEN_subscribe_to_eventnotifications_topic_timesout_THEN_retry(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, TimeoutException.class);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        CompletableFuture<Integer> exceptionallyCompletedFuture = new CompletableFuture<>();
        exceptionallyCompletedFuture.completeExceptionally(new TimeoutException());
        AtomicInteger count = new AtomicInteger();
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenAnswer(invMocks -> {
                if (count.incrementAndGet() > 1) {
                    return integerCompletableFuture;
                } else {
                    return exceptionallyCompletedFuture;
                }
        });
        CountDownLatch timeoutLogLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(l->{
            if (l.getMessage().contains(IotJobsHelper.SUBSCRIPTION_EVENT_NOTIFICATIONS_RETRY)) {
                timeoutLogLatch.countDown();
            }
        });
        iotJobsHelper.setWaitTimeToSubscribeAgain(1000);
        iotJobsHelper.subscribeToJobsTopics();
        timeoutLogLatch.await(2, TimeUnit.SECONDS);
        verify(mockIotJobsClient, times(3)).SubscribeToJobExecutionsChangedEvents(any(), eq(
                QualityOfService.AT_LEAST_ONCE), eventChangeResponseCaptor.capture());
    }

    @Test
    void GIVEN_not_connected_to_iot_WHEN_subscribe_to_jobDescriptions_topic_timesout_THEN_retry(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, TimeoutException.class);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        CompletableFuture<Integer> exceptionallyCompletedFuture = new CompletableFuture<>();
        exceptionallyCompletedFuture.completeExceptionally(new TimeoutException());
        AtomicInteger count = new AtomicInteger();
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenAnswer(invMocks -> {
            if (count.incrementAndGet() > 1) {
                return integerCompletableFuture;
            } else {
                return exceptionallyCompletedFuture;
            }
        });
        CountDownLatch timeoutLogLatch = new CountDownLatch(1);
        Slf4jLogAdapter.addGlobalListener(l->{
            if (l.getMessage().contains(IotJobsHelper.SUBSCRIPTION_EVENT_NOTIFICATIONS_RETRY)) {
                timeoutLogLatch.countDown();
            }
        });
        iotJobsHelper.setWaitTimeToSubscribeAgain(1000);
        iotJobsHelper.subscribeToJobsTopics();
        timeoutLogLatch.await(2, TimeUnit.SECONDS);
        verify(mockIotJobsClient, times(2)).SubscribeToJobExecutionsChangedEvents(any(), eq(
                QualityOfService.AT_LEAST_ONCE), eventChangeResponseCaptor.capture());
        verify(mockIotJobsClient, times(3)).SubscribeToDescribeJobExecutionAccepted(any(), eq(
                QualityOfService.AT_LEAST_ONCE), describeJobResponseCaptor.capture());
    }

    @Test
    void GIVEN_ongoing_job_deployment_with_queued_job_in_cloud_WHEN_cancel_notification_THEN_cancel_current_deployment()
            throws Exception {
        String TEST_JOB_ID = "jobToBeCancelled";


        iotJobsHelper.setDeploymentQueue(mockDeploymentQueue);
        when(mockDeploymentQueue.isEmpty()).thenReturn(true);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        DeploymentTaskMetadata mockCurrentDeploymentTaskMetadata = mock(DeploymentTaskMetadata.class);
        when(mockCurrentDeploymentTaskMetadata.getDeploymentType()).thenReturn(IOT_JOBS);
        when(mockCurrentDeploymentTaskMetadata.isCancellable()).thenReturn(true);
        when(mockDeploymentService.getCurrentDeploymentTaskMetadata()).thenReturn(mockCurrentDeploymentTaskMetadata);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionAccepted(any(), eq(
                QualityOfService.AT_LEAST_ONCE), describeJobResponseCaptor.capture());
        JobExecutionData jobExecutionData = new JobExecutionData();
        jobExecutionData.jobId = TEST_JOB_ID;
        jobExecutionData.status = JobStatus.QUEUED;
        jobExecutionData.queuedAt = new Timestamp(new Date());
        HashMap<String, Object> sampleJobDocument = new HashMap<>();
        sampleJobDocument.put("DeploymentId", TEST_JOB_ID);
        jobExecutionData.jobDocument = sampleJobDocument;
        DescribeJobExecutionResponse describeJobExecutionResponse = new DescribeJobExecutionResponse();
        describeJobExecutionResponse.execution = jobExecutionData;
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse);
        ArgumentCaptor<Deployment> deploymentArgumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(mockDeploymentQueue,times(2)).offer(deploymentArgumentCaptor.capture());
        // First queued deployment should be for cancellation and then next one for next queued job
        List<Deployment> actualDeployments = deploymentArgumentCaptor.getAllValues();
        assertTrue(actualDeployments.get(0).isCancelled());
        assertEquals(IOT_JOBS, actualDeployments.get(0).getDeploymentType());
        assertFalse(actualDeployments.get(1).isCancelled());
        assertEquals(IOT_JOBS, actualDeployments.get(1).getDeploymentType());
        assertEquals(TEST_JOB_ID, actualDeployments.get(1).getId());
    }

    @Test
    void GIVEN_ongoing_job_deployment_WHEN_notification_with_empty_jobs_list_THEN_cancel_current_deployment()
            throws Exception {
        iotJobsHelper.setDeploymentQueue(mockDeploymentQueue);
        when(mockDeploymentQueue.isEmpty()).thenReturn(true);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        DeploymentTaskMetadata mockCurrentDeploymentTaskMetadata = mock(DeploymentTaskMetadata.class);
        when(mockCurrentDeploymentTaskMetadata.getDeploymentType()).thenReturn(IOT_JOBS);
        when(mockCurrentDeploymentTaskMetadata.isCancellable()).thenReturn(true);
        when(mockDeploymentService.getCurrentDeploymentTaskMetadata()).thenReturn(mockCurrentDeploymentTaskMetadata);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToJobExecutionsChangedEvents(any(), eq(
                QualityOfService.AT_LEAST_ONCE), eventChangeResponseCaptor.capture());
        JobExecutionsChangedEvent event = new JobExecutionsChangedEvent();
        event.jobs = new HashMap<>();
        eventChangeResponseCaptor.getValue().accept(event);
        verify(mockIotJobsClient, times(2)).PublishDescribeJobExecution(any(),
                eq(QualityOfService.AT_LEAST_ONCE));
        ArgumentCaptor<Deployment> deploymentArgumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(mockDeploymentQueue).offer(deploymentArgumentCaptor.capture());
        Deployment actualDeployment = deploymentArgumentCaptor.getValue();
        assertTrue(actualDeployment.isCancelled());
        assertEquals(IOT_JOBS, actualDeployment.getDeploymentType());
    }

    @Test
    void GIVEN_ongoing_local_deployment_WHEN_notification_with_empty_jobs_list_THEN_do_nothing()
            throws Exception {
        iotJobsHelper.setDeploymentQueue(mockDeploymentQueue);
        when(mockDeploymentQueue.isEmpty()).thenReturn(true);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        DeploymentTaskMetadata mockCurrentDeploymentTaskMetadata = mock(DeploymentTaskMetadata.class);
        when(mockCurrentDeploymentTaskMetadata.getDeploymentType()).thenReturn(LOCAL);
        when(mockCurrentDeploymentTaskMetadata.isCancellable()).thenReturn(true);
        when(mockDeploymentService.getCurrentDeploymentTaskMetadata()).thenReturn(mockCurrentDeploymentTaskMetadata);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToJobExecutionsChangedEvents(any(), eq(
                QualityOfService.AT_LEAST_ONCE), eventChangeResponseCaptor.capture());
        JobExecutionsChangedEvent event = new JobExecutionsChangedEvent();
        event.jobs = new HashMap<>();
        eventChangeResponseCaptor.getValue().accept(event);
        verify(mockIotJobsClient, times(2)).PublishDescribeJobExecution(any(),
                eq(QualityOfService.AT_LEAST_ONCE));
        // Ongoing deployment is not of jobs type, it shouldn't be canceled, so nothing should be put in the queue
        verify(mockDeploymentQueue,times(0)).offer(any());
    }

    @Test
    void GIVEN_connected_to_iot_WHEN_subscribe_to_jobs_topics_THEN_get_job_description()
            throws Exception {
        String TEST_JOB_ID = "jobToReceive";
        iotJobsHelper.setDeploymentQueue(mockDeploymentQueue);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockDeploymentService.getCurrentDeploymentTaskMetadata()).thenReturn(null);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionAccepted(any(), eq(
                QualityOfService.AT_LEAST_ONCE), describeJobResponseCaptor.capture());
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionRejected(any(), eq(
                QualityOfService.AT_LEAST_ONCE), rejectedErrorCaptor.capture());
        JobExecutionData jobExecutionData = new JobExecutionData();
        jobExecutionData.jobId = TEST_JOB_ID;
        jobExecutionData.status = JobStatus.QUEUED;
        jobExecutionData.queuedAt = new Timestamp(new Date());
        HashMap<String, Object> sampleJobDocument = new HashMap<>();
        sampleJobDocument.put("DeploymentId", TEST_JOB_ID);
        jobExecutionData.jobDocument = sampleJobDocument;
        DescribeJobExecutionResponse describeJobExecutionResponse = new DescribeJobExecutionResponse();
        describeJobExecutionResponse.execution = jobExecutionData;
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse);
        ArgumentCaptor<Deployment> deploymentArgumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(mockDeploymentQueue).offer(deploymentArgumentCaptor.capture());
        Deployment actualDeployment = deploymentArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID, actualDeployment.getId());
        assertEquals(IOT_JOBS, actualDeployment.getDeploymentType());
        assertEquals("{\"DeploymentId\":\"jobToReceive\"}", actualDeployment.getDeploymentDocument());
    }

    @Test
    void GIVEN_iot_job_notifications_WHEN_duplicate_or_outdated_THEN_ignore_jobs() {
        String TEST_JOB_ID = "duplicateJob";
        iotJobsHelper.setDeploymentQueue(mockDeploymentQueue);
        CompletableFuture<Integer> integerCompletableFuture = CompletableFuture.completedFuture(1);
        when(mockIotJobsClient.SubscribeToJobExecutionsChangedEvents(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionAccepted(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockIotJobsClient.SubscribeToDescribeJobExecutionRejected(any()
                , eq(QualityOfService.AT_LEAST_ONCE), any())).thenReturn(integerCompletableFuture);
        when(mockDeploymentService.getCurrentDeploymentTaskMetadata()).thenReturn(null);
        iotJobsHelper.subscribeToJobsTopics();
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionAccepted(any(), eq(
                QualityOfService.AT_LEAST_ONCE), describeJobResponseCaptor.capture());
        verify(mockIotJobsClient, times(2)).SubscribeToDescribeJobExecutionRejected(any(), eq(
                QualityOfService.AT_LEAST_ONCE), rejectedErrorCaptor.capture());

        // Create four mock jobs
        Timestamp current = new Timestamp(new Date());
        DescribeJobExecutionResponse describeJobExecutionResponse1 = new DescribeJobExecutionResponse();
        describeJobExecutionResponse1.execution = getMockJobExecutionData(TEST_JOB_ID, current);
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse1);

        DescribeJobExecutionResponse describeJobExecutionResponse2 = new DescribeJobExecutionResponse();
        describeJobExecutionResponse2.execution = getMockJobExecutionData(TEST_JOB_ID, current);
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse2);

        DescribeJobExecutionResponse describeJobExecutionResponse3 = new DescribeJobExecutionResponse();
        describeJobExecutionResponse3.execution = getMockJobExecutionData("anyId1", new Timestamp(new Date(0)));
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse3);

        DescribeJobExecutionResponse describeJobExecutionResponse4 = new DescribeJobExecutionResponse();
        describeJobExecutionResponse4.execution = getMockJobExecutionData("anyId2", current);
        describeJobResponseCaptor.getValue().accept(describeJobExecutionResponse4);

        // Only two jobs should be queued
        ArgumentCaptor<Deployment> deploymentArgumentCaptor = ArgumentCaptor.forClass(Deployment.class);
        verify(mockDeploymentQueue, times(2)).offer(deploymentArgumentCaptor.capture());

        List<Deployment> actualDeployments = deploymentArgumentCaptor.getAllValues();
        assertEquals(2, actualDeployments.size());
        assertEquals(TEST_JOB_ID, actualDeployments.get(0).getId());
        assertEquals(IOT_JOBS, actualDeployments.get(0).getDeploymentType());
        assertEquals("{\"DeploymentId\":\"duplicateJob\"}", actualDeployments.get(0).getDeploymentDocument());

        assertEquals("anyId2", actualDeployments.get(1).getId());
        assertEquals(IOT_JOBS, actualDeployments.get(1).getDeploymentType());
        assertEquals("{\"DeploymentId\":\"anyId2\"}", actualDeployments.get(1).getDeploymentDocument());
    }

    @Test
    void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_subscribe_to_eventNotifications()
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
    void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_subscribe_to_getNextJobDescription()
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
    void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_request_next_pending_jobDoc() {

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
    void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_update_jobStatus_successfully()
    throws Exception {
        String TEST_JOB_ID = "statusUpdateSuccess";

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

        verify(mockWrapperMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace(
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
    void GIVEN_jobsClient_and_mqttConnection_WHEN_mqtt_connected_THEN_update_jobStatus_failed()
            throws Exception {
        String TEST_JOB_ID = "statusUpdateFailure";
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
            assertEquals(REJECTION_MESSAGE, e.getCause().getMessage());
        }

        UpdateJobExecutionSubscriptionRequest actualRequest = requestArgumentCaptor.getValue();
        assertEquals(TEST_JOB_ID,actualRequest.jobId);
        assertEquals(TEST_THING_NAME, actualRequest.thingName);

        verify(mockWrapperMqttClientConnection).unsubscribe(eq(IotJobsHelper.UPDATE_SPECIFIC_JOB_REJECTED_TOPIC.replace(
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
    void WHEN_mqttConnection_resumes_THEN_jobsclient_doesnt_resub_and_call_publish_persisted_deployment_status() {
        verify(mockIotJobsClient, times(1)).SubscribeToJobExecutionsChangedEvents(any(), any(), any());
        iotJobsHelper.getCallbacks().onConnectionResumed(false);
        verify(mockIotJobsClient, times(1)).SubscribeToJobExecutionsChangedEvents(any(), any(), any());
        verify(deploymentStatusKeeper).publishPersistedStatusUpdates(eq(IOT_JOBS));
    }

    private JobExecutionData getMockJobExecutionData(String jobId, Timestamp ts) {
        JobExecutionData jobExecutionData = new JobExecutionData();
        jobExecutionData.jobId = jobId;
        jobExecutionData.status = JobStatus.QUEUED;
        jobExecutionData.queuedAt = ts;
        HashMap<String, Object> sampleJobDocument = new HashMap<>();
        sampleJobDocument.put("DeploymentId", jobId);
        jobExecutionData.jobDocument = sampleJobDocument;
        return jobExecutionData;
    }
}
