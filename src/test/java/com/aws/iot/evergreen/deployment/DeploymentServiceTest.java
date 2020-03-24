/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith(MockitoExtension.class)
public class DeploymentServiceTest extends EGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String TEST_JOB_ID_2 = "TEST_JOB_2";
    private static final String MOCK_THING_NAME = "MockThingName";
    private static final String MOCK_CLIENT_ENDPOINT = "MockClientEndpoint";
    private static final String MOCK_CERTIFICATE_PATH = "/home/secrets/certificate.pem.cert";
    private static final String MOCK_PRIVATE_KEY_PATH = "/home/secrets/privateKey.pem.key";
    private static final String MOCK_ROOTCA_PATH = "/home/secrets/rootCA.pem";

    @Mock
    Topic deviceParamTopic;

    @Mock
    IotJobsHelper mockIotJobsHelper;

    @Mock
    DeploymentService.IotJobsHelperFactory mockIotJobsHelperFactory;

    @Spy
    Kernel mockKernel;

    @Mock
    ExecutorService mockExecutorService;

    @Mock
    private DependencyResolver dependencyResolver;

    @Mock
    private PackageStore packageStore;

    @Mock
    private KernelConfigResolver kernelConfigResolver;

    @Captor
    ArgumentCaptor<Consumer<JobExecutionsChangedEvent>> jobEventConsumerCaptor;

    @Captor
    ArgumentCaptor<Consumer<DescribeJobExecutionResponse>> describeJobConsumerCaptor;

    DeploymentService deploymentService;


    @Nested
    class DeploymentServiceInitializedWithMocks {

        @BeforeEach
        public void setup() {
            // initialize Evergreen service specific mocks
            serviceFullName = "DeploymentService";
            initializeMockedConfig();
            when(stateTopic.getOnce()).thenReturn(State.INSTALLED);

            when(config.findLeafChild(Mockito.any())).thenAnswer(invocationOnMock -> {
                String parameterName = invocationOnMock.getArguments()[0].toString();
                if (parameterName.equals(DeploymentService.DEVICE_PARAM_THING_NAME)) {
                    when(deviceParamTopic.getOnce()).thenReturn(MOCK_THING_NAME);
                    return deviceParamTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_MQTT_CLIENT_ENDPOINT)) {
                    when(deviceParamTopic.getOnce()).thenReturn(MOCK_CLIENT_ENDPOINT);
                    return deviceParamTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_PRIVATE_KEY_PATH)) {
                    when(deviceParamTopic.getOnce()).thenReturn(MOCK_PRIVATE_KEY_PATH);
                    return deviceParamTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_CERTIFICATE_FILE_PATH)) {
                    when(deviceParamTopic.getOnce()).thenReturn(MOCK_CERTIFICATE_PATH);
                    return deviceParamTopic;
                } else if (parameterName.equals(DeploymentService.DEVICE_PARAM_ROOT_CA_PATH)) {
                    when(deviceParamTopic.getOnce()).thenReturn(MOCK_ROOTCA_PATH);
                    return deviceParamTopic;
                }
                return deviceParamTopic;
            });

            //Deployment service specific mocks
            when(mockKernel.deTilde(anyString())).thenAnswer(invocationOnMock -> {
                return invocationOnMock.getArguments()[0].toString();
            });
            when(mockIotJobsHelperFactory.getIotJobsHelper(anyString(), anyString(), anyString(), anyString(),
                    anyString(), any(MqttClientConnectionEvents.class)))
                    .thenReturn(mockIotJobsHelper);

            //Creating the class to be tested
            deploymentService =
                    new DeploymentService(config, mockIotJobsHelperFactory, mockExecutorService, mockKernel,
                            dependencyResolver, packageStore, kernelConfigResolver);
        }


        @Nested
        public class ServiceRunningWithDefaultPollFrequency {

            @BeforeEach
            public void startService() throws InterruptedException {
                startDeploymentServiceInAnotherThread();
            }

            @AfterEach
            public void tearDown() {
                deploymentService.shutdown();
            }

            @Test
            public void GIVEN_device_configured_THEN_start_deployment_service()
                    throws ExecutionException, InterruptedException {

                verify(mockIotJobsHelper).subscribeToEventNotifications(any());
                verify(mockIotJobsHelper).subscribeToGetNextJobDecription(any(), any());
            }

            @Test
            public void GIVEN_subscribed_to_EventNotifications_WHEN_new_job_queued_THEN_process_notification()
                    throws Exception {

                verify(mockIotJobsHelper).subscribeToEventNotifications(jobEventConsumerCaptor.capture());
                Consumer<JobExecutionsChangedEvent> consumer = jobEventConsumerCaptor.getValue();
                JobExecutionsChangedEvent response = new JobExecutionsChangedEvent();
                response.jobs = getTestJobs();
                consumer.accept(response);
                verify(mockIotJobsHelper).requestNextPendingJobDocument();
            }

            @Test
            public void GIVEN_subscribed_to_EventNotifications_WHEN_job_finished_and_no_job_THEN_process_notification()
                    throws Exception {

                verify(mockIotJobsHelper).subscribeToEventNotifications(jobEventConsumerCaptor.capture());
                Consumer<JobExecutionsChangedEvent> consumer = jobEventConsumerCaptor.getValue();
                JobExecutionsChangedEvent response = new JobExecutionsChangedEvent();
                response.jobs = new HashMap<>();
                consumer.accept(response);
                verify(mockIotJobsHelper, times(0)).requestNextPendingJobDocument();
            }

        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_report_succeeded_job_status()
                throws ExecutionException, InterruptedException {
            CompletableFuture<Void> mockFuture = new CompletableFuture<>();
            mockFuture.complete(null);
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            CompletableFuture<Integer> cf = new CompletableFuture<>();
            cf.complete(1);
            when(mockIotJobsHelper.updateJobStatus(any(), any(), any())).thenReturn(cf);
            startDeploymentServiceInAnotherThread();

            verify(mockIotJobsHelper).subscribeToGetNextJobDecription(describeJobConsumerCaptor.capture(), any());
            Consumer<DescribeJobExecutionResponse> consumer = describeJobConsumerCaptor.getValue();
            DescribeJobExecutionResponse response = new DescribeJobExecutionResponse();
            response.execution = getTestJobExecutionData();
            consumer.accept(response);

            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS), any());
            verify(mockExecutorService).submit(any(DeploymentTask.class));
            //Wait for the enough time after which deployment service would have updated the status of job
            Thread.sleep(Duration.ofSeconds(2).toMillis());

            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED), any());
            deploymentService.shutdown();
        }



        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_fails_THEN_report_failed_job_status()
                throws ExecutionException, InterruptedException {
            CompletableFuture<Void> mockFuture = new CompletableFuture<>();
            Throwable t = new NonRetryableDeploymentTaskFailureException(null);
            mockFuture.completeExceptionally(t);
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            CompletableFuture<Integer> cf = new CompletableFuture<>();
            cf.complete(1);
            when(mockIotJobsHelper.updateJobStatus(any(), any(), any())).thenReturn(cf);
            startDeploymentServiceInAnotherThread();

            verify(mockIotJobsHelper).subscribeToGetNextJobDecription(describeJobConsumerCaptor.capture(), any());
            Consumer<DescribeJobExecutionResponse> consumer = describeJobConsumerCaptor.getValue();
            DescribeJobExecutionResponse response = new DescribeJobExecutionResponse();
            response.execution = getTestJobExecutionData();
            consumer.accept(response);

            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS), any());
            verify(mockExecutorService).submit(any(DeploymentTask.class));
            //Wait for the enough time after which deployment service would have updated the status of job
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.FAILED), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_WHEN_mqtt_breaks_on_success_job_update_THEN_persist_deployment_update_later()
                throws ExecutionException, InterruptedException {
            CompletableFuture<Void> mockFuture = new CompletableFuture<Void>();
            mockFuture.complete(null);
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            CompletableFuture<Integer> exceptionCf = new CompletableFuture<>();
            exceptionCf.completeExceptionally(new Throwable());
            CompletableFuture<Integer> cf = new CompletableFuture<>();
            cf.complete(1);
            doReturn(cf).when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                    eq(null));
            doReturn(exceptionCf, cf).when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.SUCCEEDED),
                    eq(null));

            InOrder mockExecutorServiceInOrder = inOrder(mockExecutorService);
            InOrder mockIotJobsHelperInOrder = Mockito.inOrder(mockIotJobsHelper);

            startDeploymentServiceInAnotherThread();
            ArgumentCaptor<MqttClientConnectionEvents> callbackCaptor =
                    ArgumentCaptor.forClass(MqttClientConnectionEvents.class);
            verify(mockIotJobsHelperFactory).getIotJobsHelper(anyString(), anyString(),
                    anyString(), anyString(),
                    anyString(), callbackCaptor.capture());

            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToGetNextJobDecription(describeJobConsumerCaptor.capture(), any());
            Consumer<DescribeJobExecutionResponse> consumer = describeJobConsumerCaptor.getValue();
            DescribeJobExecutionResponse response = new DescribeJobExecutionResponse();
            response.execution = getTestJobExecutionData();
            consumer.accept(response);
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.IN_PROGRESS), any());
            mockExecutorServiceInOrder.verify(mockExecutorService).submit(any(DeploymentTask.class));

            //Wait for the enough time after which deployment service would have updated the status of job
            Thread.sleep(Duration.ofSeconds(5).toMillis());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.SUCCEEDED), any());
            Topics processedDeployments = mockKernel.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                    DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
            assertEquals(1,processedDeployments.size());


            callbackCaptor.getValue().onConnectionInterrupted(1);
            MqttClientConnectionEvents callback = callbackCaptor.getValue();
            callback.onConnectionResumed(true);

            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.SUCCEEDED), any());
            processedDeployments = mockKernel.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                    DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
            assertEquals(0,processedDeployments.size());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_service_running_WHEN_mqtt_connection_interrupted_THEN_subscribe_to_topics_again()
                throws ExecutionException, InterruptedException {

            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());

            InOrder mockIotJobsHelperInOrder = Mockito.inOrder(mockIotJobsHelper);
            startDeploymentServiceInAnotherThread();
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToEventNotifications(any());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToGetNextJobDecription(any(), any());
            ArgumentCaptor<MqttClientConnectionEvents> callbackCaptor =
                    ArgumentCaptor.forClass(MqttClientConnectionEvents.class);
            verify(mockIotJobsHelperFactory).getIotJobsHelper(anyString(), anyString(),
                    anyString(), anyString(),
                    anyString(), callbackCaptor.capture());

            callbackCaptor.getValue().onConnectionInterrupted(1);
            MqttClientConnectionEvents callback = callbackCaptor.getValue();
            callback.onConnectionResumed(true);

            //Wait for the DeploymentService thread to run at least one iteration of the loop
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToEventNotifications(any());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToGetNextJobDecription(any(), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_multiple_deployment_jobs_WHEN_mqtt_breaks_THEN_persist_deployments_update_later()
                throws ExecutionException, InterruptedException {
            CompletableFuture<Void> mockFuture = new CompletableFuture<Void>();
            mockFuture.complete(null);
            CompletableFuture<Void> mockFutureWitException = new CompletableFuture<Void>();
            Throwable t = new NonRetryableDeploymentTaskFailureException(null);
            mockFutureWitException.completeExceptionally(t);
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture, mockFutureWitException);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            CompletableFuture<Integer> exceptionCf = new CompletableFuture<>();
            exceptionCf.completeExceptionally(new Throwable());
            CompletableFuture<Integer> cf = new CompletableFuture<>();
            cf.complete(1);
            doReturn(cf).when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                    eq(null));
            doReturn(cf).when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.IN_PROGRESS),
                    eq(null));
            doReturn(exceptionCf, cf).when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.SUCCEEDED),
                    eq(null));
            doReturn(exceptionCf, cf).when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_2),
                    eq(JobStatus.FAILED),
                    eq(null));

            InOrder mockExecutorServiceInOrder = inOrder(mockExecutorService);
            InOrder mockIotJobsHelperInOrder = Mockito.inOrder(mockIotJobsHelper);

            startDeploymentServiceInAnotherThread();
            ArgumentCaptor<MqttClientConnectionEvents> callbackCaptor =
                    ArgumentCaptor.forClass(MqttClientConnectionEvents.class);
            verify(mockIotJobsHelperFactory).getIotJobsHelper(anyString(), anyString(),
                    anyString(), anyString(),
                    anyString(), callbackCaptor.capture());

            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToGetNextJobDecription(describeJobConsumerCaptor.capture(), any());
            Consumer<DescribeJobExecutionResponse> consumer = describeJobConsumerCaptor.getValue();
            DescribeJobExecutionResponse response = new DescribeJobExecutionResponse();

            response.execution = getTestJobExecutionData();
            consumer.accept(response);
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.IN_PROGRESS), any());
            mockExecutorServiceInOrder.verify(mockExecutorService).submit(any(DeploymentTask.class));

            //Wait for the enough time after which deployment service would have updated the status of job
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.SUCCEEDED), any());

            Topics processedDeployments = mockKernel.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                    DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
            assertEquals(1,processedDeployments.size());

            response.execution = getTestJobExecutionData(TEST_JOB_ID_2);
            consumer.accept(response);
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_2),
                    eq(JobStatus.IN_PROGRESS), any());
            mockExecutorServiceInOrder.verify(mockExecutorService).submit(any(DeploymentTask.class));

            //Wait for the enough time after which deployment service would have updated the status of job
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_2),
                    eq(JobStatus.FAILED), any());
            processedDeployments = mockKernel.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                    DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
            assertEquals(2,processedDeployments.size());

            callbackCaptor.getValue().onConnectionInterrupted(1);
            MqttClientConnectionEvents callback = callbackCaptor.getValue();
            callback.onConnectionResumed(true);

            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1),
                    eq(JobStatus.SUCCEEDED), any());
            mockIotJobsHelperInOrder.verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_2),
                    eq(JobStatus.FAILED), any());
            processedDeployments = mockKernel.lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                    DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
            assertEquals(0,processedDeployments.size());
            deploymentService.shutdown();
        }

    }



    private void startDeploymentServiceInAnotherThread() throws InterruptedException {
        Thread t = new Thread(() -> deploymentService.startup());
        t.start();
        //Waiting for other thread to start
        //TODO: Make it more robust by checking for a service state instead of sleeping.
        // With mock kernel the state transition does not get triggered
        Thread.sleep(1000);

    }

    private JobExecutionData getTestJobExecutionData(String... jobId) {
        JobExecutionData jobExecutionData = new JobExecutionData();
        jobExecutionData.status = JobStatus.QUEUED;
        jobExecutionData.jobId = TEST_JOB_ID_1;
        if(jobId.length > 0) {
            jobExecutionData.jobId = jobId[0];
        }
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
