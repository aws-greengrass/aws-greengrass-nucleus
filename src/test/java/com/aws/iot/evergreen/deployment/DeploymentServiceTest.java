/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith(MockitoExtension.class)
public class DeploymentServiceTest extends EGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String TEST_JOB_ID_2 = "TEST_JOB_2";
    private static final String CONNECTION_ERROR = "Connection error";

    @Mock
    IotJobsHelper mockIotJobsHelper;

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

    DeploymentService deploymentService;
    LinkedBlockingQueue<Deployment> deploymentsQueue;

    @BeforeEach
    public void setup() {
        // initialize Evergreen service specific mocks
        serviceFullName = "DeploymentService";
        initializeMockedConfig();
        when(stateTopic.getOnce()).thenReturn(State.INSTALLED);
        //Creating the class to be tested
        deploymentService =
                new DeploymentService(config, mockExecutorService, mockKernel,
                        dependencyResolver, packageStore, kernelConfigResolver, mockIotJobsHelper);
        deploymentsQueue = new LinkedBlockingQueue<>();
        deploymentService.setDeploymentsQueue(deploymentsQueue);
    }

    @Nested
    public class ServiceStartup extends ExceptionLogProtector {

        @BeforeEach
        public void startService() throws Exception {
            startDeploymentServiceInAnotherThread();
        }

        @AfterEach
        public void tearDown() {
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_device_configured_THEN_start_deployment_service()
                throws Exception {
            verify(mockIotJobsHelper).connect();
        }

        @Test
        public void GIVEN_deployment_service_running_WHEN_connection_resumed_THEN_subscriptions_redone()
                throws Exception {
            deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
            verify(mockIotJobsHelper).connect();
            MqttClientConnectionEvents callbacks = deploymentService.callbacks;
            callbacks.onConnectionResumed(true);
            //Wait for the subscription to be executed in separate thread
            Thread.sleep(Duration.ofSeconds(1).toMillis());
            verify(mockIotJobsHelper).subscribeToJobsTopics();
        }
    }

    @Nested
    class DeploymentInProgress extends ExceptionLogProtector {

        CompletableFuture<Void> mockFuture = new CompletableFuture<>();

        @BeforeEach
        public void setup() throws Exception {
            Topics processedDeploymentsTopics =
                    mockKernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                            DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
                            DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
            when(config.createInteriorChild(eq(DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS)))
                    .thenReturn(processedDeploymentsTopics);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            mockFuture.complete(null);
            deploymentsQueue.put(new Deployment("{\"DeploymentId\":\"testId\"}",
                    Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_report_succeeded_job_status()
                throws Exception {
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();
            verify(mockIotJobsHelper).connect();

            //Wait for the enough time after which deployment service would have processed the job from the queue
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                    any());
            verify(mockExecutorService).submit(any(DeploymentTask.class));
            verify(mockIotJobsHelper)
                    .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED), any());
            deploymentService.shutdown();
        }


        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_fails_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<Void> mockFutureWithException = new CompletableFuture<>();
            ignoreExceptionUltimateCauseOfType(NonRetryableDeploymentTaskFailureException.class);
            Throwable t = new NonRetryableDeploymentTaskFailureException(null);
            mockFutureWithException.completeExceptionally(t);
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFutureWithException);
            startDeploymentServiceInAnotherThread();

            // Wait for the enough time after which deployment service would have processed the job from the queue
            Thread.sleep(Duration.ofSeconds(2).toMillis());
            verify(mockExecutorService).submit(any(DeploymentTask.class));
            verify(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                    any());
            verify(mockIotJobsHelper)
                    .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.FAILED), any());
            deploymentService.shutdown();
        }

        @Nested
        public class MqttConnectionBreaks extends ExceptionLogProtector {

            ArgumentCaptor<MqttClientConnectionEvents> mqttEventCaptor;

            @BeforeEach
            public void setup() {
                mqttEventCaptor =
                        ArgumentCaptor.forClass(MqttClientConnectionEvents.class);
            }

            @Test
            public void GIVEN_deployment_job_WHEN_mqtt_breaks_on_success_job_update_THEN_persist_deployment_update_later()
                    throws Exception {
                when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
                ignoreExceptionUltimateCauseWithMessage(CONNECTION_ERROR);
                ExecutionException e = new ExecutionException(new MqttException(CONNECTION_ERROR));
                doNothing().when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                        any());
                doThrow(e).doNothing().when(mockIotJobsHelper)
                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED), any());
                InOrder mockIotJobsHelperInOrder = inOrder(mockIotJobsHelper);

                startDeploymentServiceInAnotherThread();
                //Wait for the enough time after which deployment service would have processed the job from the queue
                Thread.sleep(Duration.ofSeconds(2).toMillis());

                verify(mockIotJobsHelper).connect();
                MqttClientConnectionEvents callbacks = deploymentService.callbacks;

                callbacks.onConnectionInterrupted(1);
                Topics processedDeployments = mockKernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
                assertEquals(1, processedDeployments.size());

                //Using actual executor service for running the method in a separate thread
                deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
                callbacks.onConnectionResumed(true);
                //Wait for job statuses to be updated
                Thread.sleep(Duration.ofSeconds(1).toMillis());
                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, times(2))
                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED),  any());
                processedDeployments = mockKernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
                assertEquals(0, processedDeployments.size());
            }

            @Test
            public void GIVEN_deployment_service_running_WHEN_mqtt_connection_resumed_THEN_subscribe_to_topics_again()
                    throws Exception {
                when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
                InOrder mockIotJobsHelperInOrder = inOrder(mockIotJobsHelper);
                startDeploymentServiceInAnotherThread();
                Thread.sleep(Duration.ofSeconds(2).toMillis());

                deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
                MqttClientConnectionEvents callbacks = deploymentService.callbacks;

                callbacks.onConnectionInterrupted(1);
                callbacks.onConnectionResumed(true);
                //Wait for the DeploymentService thread to run at least one iteration of the loop
                Thread.sleep(Duration.ofSeconds(2).toMillis());
                mockIotJobsHelperInOrder.verify(mockIotJobsHelper).subscribeToJobsTopics();
            }

            @Test
            public void GIVEN_multiple_deployment_jobs_WHEN_mqtt_breaks_THEN_persist_deployments_update_later()
                    throws Exception {
                CompletableFuture<Void> mockFutureWitException = new CompletableFuture<>();
                ignoreExceptionUltimateCauseOfType(NonRetryableDeploymentTaskFailureException.class);
                Throwable t = new NonRetryableDeploymentTaskFailureException(null);
                mockFutureWitException.completeExceptionally(t);
                doReturn(mockFuture, mockFutureWitException).when(mockExecutorService).submit(any(DeploymentTask.class));
                ignoreExceptionUltimateCauseWithMessage(CONNECTION_ERROR);
                ExecutionException executionException = new ExecutionException(new MqttException(CONNECTION_ERROR));
                doNothing().when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                        any());
                doNothing().when(mockIotJobsHelper).updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.IN_PROGRESS),
                        any());
                doThrow(executionException).doThrow(executionException).doNothing().doNothing()
                        .when(mockIotJobsHelper).updateJobStatus(any(), or(eq(JobStatus.SUCCEEDED),
                        eq(JobStatus.FAILED)),  any());

                InOrder mockIotJobsHelperInOrder = inOrder(mockIotJobsHelper);

                startDeploymentServiceInAnotherThread();
                verify(mockIotJobsHelper).connect();

                //Wait for the enough time after which deployment service would have updated the status of job
                Thread.sleep(Duration.ofSeconds(2).toMillis());
                //Submit TEST_JOB_2
                deploymentsQueue.put(new Deployment("{\"DeploymentId\":\"testId\"}",
                        Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_2));
                //Wait for the enough time after which deployment service would have updated the status of job
                Thread.sleep(Duration.ofSeconds(2).toMillis());
                //Using actual executor service for running the method in a separate thread
                deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
                MqttClientConnectionEvents callbacks = deploymentService.callbacks;
                callbacks.onConnectionResumed(true);
                //Wait for main thread to update the persisted deployment statuses
                Thread.sleep(Duration.ofSeconds(2).toMillis());

                mockIotJobsHelperInOrder.verify(mockIotJobsHelper)
                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
                                any());
                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, times(3))
                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED),  any());
                mockIotJobsHelperInOrder.verify(mockIotJobsHelper)
                        .updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.IN_PROGRESS),
                                any());
                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, times(1))
                        .updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.FAILED),  any());

                Topics processedDeployments = mockKernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
                assertEquals(0, processedDeployments.size());
            }
        }
    }

    private void startDeploymentServiceInAnotherThread() throws InterruptedException {
        Thread t = new Thread(() -> {
            try {
                deploymentService.startup();
            } catch (InterruptedException e) {
                fail("Deployment service thread interrupted");
            }
        });
        t.start();
        //Waiting for other thread to start
        //TODO: Make it more robust by checking for a service state instead of sleeping.
        // With mock kernel the state transition does not get triggered
        Thread.sleep(1000);
    }
}
