/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentResult.DeploymentStatus;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logging.impl.Slf4jLogAdapter;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.verification.VerificationWithTimeout;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static com.github.grantwest.eventually.EventuallyLambdaMatcher.eventuallyEval;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.or;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith({MockitoExtension.class, EGExtension.class})
public class DeploymentServiceTest extends EGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String TEST_JOB_ID_2 = "TEST_JOB_2";
    private static final String CONNECTION_ERROR = "Connection error";
    private static final VerificationWithTimeout WAIT_FOUR_SECONDS = timeout(Duration.ofSeconds(4).toMillis());

    @Spy
    Kernel mockKernel;

    @Mock
    ExecutorService mockExecutorService;

    @Mock
    private DependencyResolver dependencyResolver;

    @Mock
    private PackageManager packageManager;

    @Mock
    private KernelConfigResolver kernelConfigResolver;

    @Mock
    private DeploymentConfigMerger deploymentConfigMerger;

    @Mock
    private DeploymentStatusKeeper deploymentStatusKeeper;

    DeploymentService deploymentService;
    LinkedBlockingQueue<Deployment> deploymentsQueue;
    private Thread deploymentServiceThread;

    @BeforeEach
    public void setup() {
        // initialize Evergreen service specific mocks
        serviceFullName = "DeploymentService";
        initializeMockedConfig();
        when(stateTopic.getOnce()).thenReturn(State.INSTALLED);

        // Creating the class to be tested
        deploymentService = new DeploymentService(config, mockExecutorService, dependencyResolver, packageManager,
                kernelConfigResolver, deploymentConfigMerger, deploymentStatusKeeper, context);
        deploymentService.postInject();

        deploymentsQueue = new LinkedBlockingQueue<>();
        deploymentService.setDeploymentsQueue(deploymentsQueue);
    }

    @AfterEach
    void afterEach() throws InterruptedException {
        deploymentService.shutdown();
        if (deploymentServiceThread != null && deploymentServiceThread.isAlive()) {
            deploymentServiceThread.interrupt();
        }
        mockKernel.shutdown();
    }

//    @Nested
//    public class ServiceStartup {
//
//        @BeforeEach
//        public void startService() throws Exception {
//            startDeploymentServiceInAnotherThread();
//        }
//
//        @AfterEach
//        public void tearDown() throws InterruptedException {
//            deploymentService.shutdown();
//        }
//
////        @Test
////        public void GIVEN_device_configured_THEN_start_deployment_service() throws Exception {
////            verify(mockIotJobsHelper).connect();
////        }
//
//        @Test
//        public void GIVEN_deployment_service_running_WHEN_connection_resumed_THEN_subscriptions_redone()
//                throws Exception {
//            deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
//            //verify(mockIotJobsHelper).connect();
//            MqttClientConnectionEvents callbacks = deploymentService.callbacks;
//            callbacks.onConnectionResumed(true);
//            verify(mockIotJobsHelper, timeout(200)).subscribeToJobsTopics();
//        }
//    }

    @Nested
    class DeploymentInProgress {
        CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();

        @BeforeEach
        public void setup() throws Exception {

//            Topics processedDeploymentsTopics = mockKernel.getConfig()
//                    .lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
//                            DeploymentService.DEPLOYMENT_SERVICE_TOPICS,
//                            DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS);
//            when(config.createInteriorChild(eq(DeploymentStatusKeeper.PROCESSED_DEPLOYMENTS_TOPICS)))
//                    .thenReturn(processedDeploymentsTopics);
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            deploymentsQueue.put(new Deployment("{\"DeploymentId\":\"testId\"}",
                    Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_report_succeeded_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();


            verify(deploymentStatusKeeper, timeout(1000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            verify(mockExecutorService).submit(any(DeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED), any());

            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_completes_with_non_retryable_error_THEN_report_failed_job_status(ExtensionContext context)
                throws Exception {

            CompletableFuture<DeploymentResult> mockFutureWithException = new CompletableFuture<>();
            ignoreExceptionUltimateCauseOfType(context, NonRetryableDeploymentTaskFailureException.class);

            Throwable t = new NonRetryableDeploymentTaskFailureException(null);
            mockFutureWithException.completeExceptionally(t);
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFutureWithException);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DeploymentTask.class));
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED), any());

            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_with_auto_rollback_not_requested_WHEN_deployment_process_fails_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(
                    new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, null));
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, timeout(1000)).submit(any(DeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_succeeds_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_COMPLETE, null));
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, timeout(1000)).submit(any(DeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS),
                    any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_fails_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture
                    .complete(new DeploymentResult(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, null));
            when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, timeout(1000)).submit(any(DeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS),
                    any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_fails_with_retry_THEN_retry_job(ExtensionContext context)
                throws Exception {
            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            CompletableFuture<DeploymentResult> mockFutureWithException = new CompletableFuture<>();
            ignoreExceptionUltimateCauseOfType(context, RetryableDeploymentTaskFailureException.class);
            Throwable t = new RetryableDeploymentTaskFailureException(null);
            mockFutureWithException.completeExceptionally(t);
            when(mockExecutorService.submit(any(DeploymentTask.class)))
                    .thenReturn(mockFutureWithException, mockFutureWithException,
                            mockFuture);
            startDeploymentServiceInAnotherThread();

            // Expecting three invocations, once for each retry attempt
            verify(mockExecutorService, WAIT_FOUR_SECONDS.times(3)).submit(any(DeploymentTask.class));
            InOrder statusOrdering = inOrder(deploymentStatusKeeper);
            statusOrdering.verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            statusOrdering.verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndUpdateDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED), any());
            deploymentService.shutdown();
        }

//        @Nested
//        public class MqttConnectionBreaks {
//
//            ArgumentCaptor<MqttClientConnectionEvents> mqttEventCaptor;
//
//            @BeforeEach
//            public void setup() {
//                mqttEventCaptor = ArgumentCaptor.forClass(MqttClientConnectionEvents.class);
//            }
//
//            @Test
//            public void GIVEN_deployment_job_WHEN_mqtt_breaks_on_success_job_update_THEN_persist_deployment_update_later(ExtensionContext context)
//                    throws Exception {
//                mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
//                when(mockExecutorService.submit(any(DeploymentTask.class))).thenReturn(mockFuture);
//
//                ignoreExceptionUltimateCauseWithMessage(context, CONNECTION_ERROR);
//                ExecutionException e = new ExecutionException(new MqttException(CONNECTION_ERROR));
//                doNothing().when(mockIotJobsHelper)
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS), any());
//                doThrow(e).doNothing().when(mockIotJobsHelper)
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED), any());
//                InOrder mockIotJobsHelperInOrder = inOrder(mockIotJobsHelper);
//
//                startDeploymentServiceInAnotherThread();
//
//                //verify(mockIotJobsHelper).connect();
//                //MqttClientConnectionEvents callbacks = deploymentService.callbacks;
//                //callbacks.onConnectionInterrupted(1);
//
//                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(2000))
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED),  any());
//                Topics processedDeployments = mockKernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
//                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
//                assertEquals(1, processedDeployments.size());
//
//                // Using actual executor service for running the method in a separate thread
//                deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
//                //callbacks.onConnectionResumed(true);
//
//                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(1000))
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED),  any());
//
//                assertThat(() -> processedDeployments.size(), eventuallyEval(is(0), Duration.ofMillis(100)));
//            }
//
////            @Test
////            public void GIVEN_deployment_service_running_WHEN_mqtt_connection_resumed_THEN_subscribe_to_topics_again()
////                    throws Exception {
////                InOrder mockIotJobsHelperInOrder = inOrder(mockIotJobsHelper);
////                startDeploymentServiceInAnotherThread();
////
////                deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
////                MqttClientConnectionEvents callbacks = deploymentService.callbacks;
////
////                callbacks.onConnectionInterrupted(1);
////                callbacks.onConnectionResumed(true);
////                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(1000)).subscribeToJobsTopics();
////            }
//
//            @Test
//            public void GIVEN_multiple_deployment_jobs_WHEN_mqtt_breaks_THEN_persist_deployments_update_later(ExtensionContext context)
//                    throws Exception {
//                mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
//                CompletableFuture<DeploymentResult> mockFutureWitException = new CompletableFuture<>();
//                ignoreExceptionUltimateCauseOfType(context, NonRetryableDeploymentTaskFailureException.class);
//
//                Throwable t = new NonRetryableDeploymentTaskFailureException(null);
//                mockFutureWitException.completeExceptionally(t);
//                doReturn(mockFuture, mockFutureWitException).when(mockExecutorService)
//                        .submit(any(DeploymentTask.class));
//                ignoreExceptionUltimateCauseWithMessage(context, CONNECTION_ERROR);
//                ExecutionException executionException = new ExecutionException(new MqttException(CONNECTION_ERROR));
//                doNothing().when(mockIotJobsHelper)
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS), any());
//                doNothing().when(mockIotJobsHelper)
//                        .updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.IN_PROGRESS), any());
//                doThrow(executionException).doThrow(executionException).doNothing().doNothing().when(mockIotJobsHelper)
//                        .updateJobStatus(any(), or(eq(JobStatus.SUCCEEDED), eq(JobStatus.FAILED)), any());
//
//                InOrder mockIotJobsHelperInOrder = inOrder(mockIotJobsHelper);
//
//                startDeploymentServiceInAnotherThread();
//                //verify(mockIotJobsHelper).connect();
//
//                // Wait for the enough time after which deployment service would have updated the status of job
//                Thread.sleep(Duration.ofSeconds(1).toMillis());
//                // Submit TEST_JOB_2
//                deploymentsQueue.put(new Deployment("{\"DeploymentId\":\"testId\"}",
//                        Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_2));
//                // Wait for the enough time after which deployment service would have updated the status of job
//                Thread.sleep(Duration.ofSeconds(1).toMillis());
//                // Using actual executor service for running the method in a separate thread
//
//                deploymentService.setExecutorService(mockKernel.getContext().get(ExecutorService.class));
//               // MqttClientConnectionEvents callbacks = deploymentService.callbacks;
//               // callbacks.onConnectionResumed(true);
//
//                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(1000))
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.IN_PROGRESS),
//                                any());
//                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(1000).times(3))
//                        .updateJobStatus(eq(TEST_JOB_ID_1), eq(JobStatus.SUCCEEDED),  any());
//                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(1000))
//                        .updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.IN_PROGRESS),
//                                any());
//                mockIotJobsHelperInOrder.verify(mockIotJobsHelper, timeout(1000).times(1))
//                        .updateJobStatus(eq(TEST_JOB_ID_2), eq(JobStatus.FAILED),  any());
//
//                Topics processedDeployments = mockKernel.getConfig().lookupTopics(EvergreenService.SERVICES_NAMESPACE_TOPIC,
//                        DeploymentService.DEPLOYMENT_SERVICE_TOPICS, DeploymentService.PROCESSED_DEPLOYMENTS_TOPICS);
//                assertThat(() -> processedDeployments.size(), eventuallyEval(is(0), Duration.ofMillis(100)));
//            }
//        }
    }

    private void startDeploymentServiceInAnotherThread() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(1);
        Consumer<EvergreenStructuredLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().equals("Running deployment service")) {
                cdl.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        deploymentServiceThread = new Thread(() -> {
            try {
                deploymentService.startup();
            } catch (InterruptedException ignore) {
            }
        });
        deploymentServiceThread.start();

        boolean running = cdl.await(1, TimeUnit.SECONDS);
        Slf4jLogAdapter.removeGlobalListener(listener);
        assertTrue(running, "Deployment service must be running");
    }
}
