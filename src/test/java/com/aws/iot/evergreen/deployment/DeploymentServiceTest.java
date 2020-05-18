/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.deployment.model.DeploymentResult.DeploymentStatus;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.verification.VerificationWithTimeout;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith({MockitoExtension.class, EGExtension.class})
public class DeploymentServiceTest extends EGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    //private static final String TEST_JOB_ID_2 = "TEST_JOB_2";
    //private static final String CONNECTION_ERROR = "Connection error";
    private static final VerificationWithTimeout WAIT_FOUR_SECONDS = timeout(Duration.ofSeconds(4).toMillis());

    @Spy
    Kernel mockKernel;

    @Mock
    ExecutorService mockExecutorService;
    DeploymentService deploymentService;
    LinkedBlockingQueue<Deployment> deploymentsQueue;
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
    void afterEach() {
        deploymentService.shutdown();
        if (deploymentServiceThread != null && deploymentServiceThread.isAlive()) {
            deploymentServiceThread.interrupt();
        }
        mockKernel.shutdown();
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

    @Nested
    class DeploymentInProgress {
        CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();

        @BeforeEach
        public void setup() throws Exception {
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


            verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            verify(mockExecutorService).submit(any(DeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
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
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
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
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
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
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS),
                    any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
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
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS),
                    any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
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
            statusOrdering.verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS), any());
            statusOrdering.verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED), any());
            deploymentService.shutdown();
        }

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
