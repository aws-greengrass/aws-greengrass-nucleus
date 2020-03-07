/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.kernel.Kernel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.internal.verification.Times;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DeploymentServiceTest {

    private static final String MOCK_DEVICE_PARAMETER = "mockDeviceParameter";
    private static final String EVERGREEN_SERVICE_FULL_NAME = "DeploymentService";
    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String MOCK_THING_NAME = "MockThingName";
    private static final String MOCK_CLIENT_ENDPOINT = "MockClientEndpoint";
    private static final String MOCK_CERTIFICATE_PATH = "/home/secrets/certificate.pem.cert";
    private static final String MOCK_PRIVATE_KEY_PATH = "/home/secrets/privateKey.pem.key";
    private static final String MOCK_ROOTCA_PATH = "/home/secrets/rootCA.pem";

    @Mock
    Topic stateTopic;

    @Mock
    Topic requiresTopic;

    @Mock
    Topics mockConfig;

    @Mock
    Context mockContext;

    @Mock
    Topic mockTopic;

    @Mock
    IotJobsHelper mockIotJobsHelper;

    @Mock
    DeploymentService.IotJobsHelperFactory mockIotJobsHelperFactory;

    @Mock
    Kernel mockKernel;

    @Mock
    ExecutorService mockExecutorService;

    @Captor
    ArgumentCaptor<Consumer<JobExecutionsChangedEvent>> jobEventConsumerCaptor;

    @Captor
    ArgumentCaptor<Consumer<DescribeJobExecutionResponse>> describeJobConsumerCaptor;

    @Captor
    ArgumentCaptor<Consumer<RejectedError>> rejectedErrorConsumerCaptor;

    DeploymentService deploymentService;

    @Nested
    class DeploymentServiceInitializedWithMocks {

        @BeforeEach
        public void setup() {
            //Evergreen service specific mocks
            when(mockConfig.createLeafChild(eq("_State"))).thenReturn(stateTopic);
            when(stateTopic.getOnce()).thenReturn(State.INSTALLED);
            when(mockConfig.createLeafChild(eq("requires"))).thenReturn(requiresTopic);
            when(mockConfig.getFullName()).thenReturn(EVERGREEN_SERVICE_FULL_NAME);
            when(requiresTopic.dflt(Mockito.any())).thenReturn(requiresTopic);

            when(mockConfig.findLeafChild(Mockito.any())).thenAnswer(invocationOnMock -> {
                String parameterName = invocationOnMock.getArguments()[0].toString();
                if (parameterName.equals(DeploymentService.DEVICE_PARAM_THING_NAME)) {
                    when(mockTopic.getOnce()).thenReturn(MOCK_THING_NAME);
                    return mockTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT)) {
                    when(mockTopic.getOnce()).thenReturn(MOCK_CLIENT_ENDPOINT);
                    return mockTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_PRIVATE_KEY_PATH)) {
                    when(mockTopic.getOnce()).thenReturn(MOCK_PRIVATE_KEY_PATH);
                    return mockTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_CERTIFICATE_FILE_PATH)) {
                    when(mockTopic.getOnce()).thenReturn(MOCK_CERTIFICATE_PATH);
                    return mockTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_ROOT_CA_PATH)) {
                    when(mockTopic.getOnce()).thenReturn(MOCK_ROOTCA_PATH);
                    return mockTopic;
                }

                return mockTopic;
            });
            when(mockConfig.getFullName()).thenReturn("DeploymentService");
            when(mockConfig.getContext()).thenReturn(mockContext);

            //Deployment service specific mocks
            when(mockKernel.deTilde(anyString())).thenAnswer(invocationOnMock -> {
                return invocationOnMock.getArguments()[0].toString();
            });
            when(mockIotJobsHelperFactory.getIotJobsHelper(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(mockIotJobsHelper);

            //Creating the class to be tested
            deploymentService =
                    new DeploymentService(mockConfig, mockIotJobsHelperFactory, mockExecutorService, mockKernel);
        }

        @Test
        public void GIVEN_DescribeJobNotification_WHEN_NewJob_THEN_ProcessNotificationSuccessfully()
                throws ExecutionException, InterruptedException {
            CompletableFuture<Boolean> mockBooleanFuture = mock(CompletableFuture.class);
            when(mockBooleanFuture.get()).thenReturn(Boolean.TRUE);
            when(mockExecutorService.submit(any(DeploymentProcess.class))).thenReturn(mockBooleanFuture);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            startDeploymentServiceInAnotherThread();

            verify(mockIotJobsHelper).subscribeToGetNextJobDecription(describeJobConsumerCaptor.capture(), any());
            Consumer<DescribeJobExecutionResponse> consumer = describeJobConsumerCaptor.getValue();
            DescribeJobExecutionResponse response = new DescribeJobExecutionResponse();
            response.execution = getTestJobExecutionData();
            consumer.accept(response);
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS), any());
            verify(mockExecutorService).submit(any(DeploymentProcess.class));
            //Wait for the deploymentFrequency after which deployment service will check for the status of future
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED), any());
        }

        @Test
        public void GIVEN_DescribeJobNotification_WHEN_NewJob_THEN_ProcessNotificationUnSuccessfully()
                throws ExecutionException, InterruptedException {
            CompletableFuture<Boolean> mockBooleanFuture = mock(CompletableFuture.class);
            when(mockBooleanFuture.get()).thenReturn(Boolean.FALSE);
            when(mockExecutorService.submit(any(DeploymentProcess.class))).thenReturn(mockBooleanFuture);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            startDeploymentServiceInAnotherThread();

            verify(mockIotJobsHelper).subscribeToGetNextJobDecription(describeJobConsumerCaptor.capture(), any());
            Consumer<DescribeJobExecutionResponse> consumer = describeJobConsumerCaptor.getValue();
            DescribeJobExecutionResponse response = new DescribeJobExecutionResponse();
            response.execution = getTestJobExecutionData();
            consumer.accept(response);
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS), any());
            verify(mockExecutorService).submit(any(DeploymentProcess.class));
            //Wait for the deploymentFrequency after which deployment service will check for the status of future
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.FAILED), any());
        }

        @Nested
        public class WithDefaultServiceRunning {

            @BeforeEach
            public void startService() throws InterruptedException {
                startDeploymentServiceInAnotherThread();
            }

            @AfterEach
            public void tearDown() {
                deploymentService.shutdown();
            }

            @Test
            public void GIVEN_DeviceConfiguration_THEN_StartDeploymentService()
                    throws ExecutionException, InterruptedException {

                verify(mockIotJobsHelper).subscribeToEventNotifications(any());
                verify(mockIotJobsHelper).subscribeToGetNextJobDecription(any(), any());
            }

            @Test
            public void GIVEN_EventNotification_WHEN_NewJobQueued_THEN_ProcessNotification()
                    throws ExecutionException, InterruptedException {

                verify(mockIotJobsHelper).subscribeToEventNotifications(jobEventConsumerCaptor.capture());
                Consumer<JobExecutionsChangedEvent> consumer = jobEventConsumerCaptor.getValue();
                JobExecutionsChangedEvent response = new JobExecutionsChangedEvent();
                response.jobs = getTestJobs();
                consumer.accept(response);
                verify(mockIotJobsHelper).requestNextPendingJobDocument();
            }

            @Test
            public void GIVEN_EventNotification_WHEN_JobFinishedNoNewJob_THEN_ProcessNotification()
                    throws ExecutionException, InterruptedException {

                verify(mockIotJobsHelper).subscribeToEventNotifications(jobEventConsumerCaptor.capture());
                Consumer<JobExecutionsChangedEvent> consumer = jobEventConsumerCaptor.getValue();
                JobExecutionsChangedEvent response = new JobExecutionsChangedEvent();
                response.jobs = new HashMap<>();
                consumer.accept(response);
                verify(mockIotJobsHelper, times(0)).requestNextPendingJobDocument();
            }

        }

    }

    private void startDeploymentServiceInAnotherThread() throws InterruptedException {
        Thread t = new Thread(() -> deploymentService.startup());
        t.start();
        //let the other thread start
        Thread.sleep(100);
    }

    private JobExecutionData getTestJobExecutionData() {
        JobExecutionData jobExecutionData = new JobExecutionData();
        jobExecutionData.status = JobStatus.QUEUED;
        jobExecutionData.jobId = TEST_JOB_ID_1;
        HashMap<String, Object> jobDocument = new HashMap<>();
        jobDocument.put("DeploymentId", "testUuid");
        jobExecutionData.jobDocument = jobDocument;
        return jobExecutionData;
    }

    private HashMap<JobStatus, List<JobExecutionSummary>> getTestJobs() {
        HashMap<JobStatus, List<JobExecutionSummary>> pendingTestJobs = new HashMap<>();
        List<JobExecutionSummary> queuedJobs = new ArrayList<>();
        JobExecutionSummary job1 = new JobExecutionSummary();
        job1.jobId = TEST_JOB_ID_1;
        queuedJobs.add(job1);
        pendingTestJobs.put(JobStatus.QUEUED, queuedJobs);
        return pendingTestJobs;
    }
}
