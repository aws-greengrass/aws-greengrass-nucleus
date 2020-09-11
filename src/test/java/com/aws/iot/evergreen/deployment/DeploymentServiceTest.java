/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.CaseInsensitiveString;
import com.aws.iot.evergreen.config.Node;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
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
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.mockito.verification.VerificationWithTimeout;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.iot.evergreen.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.iot.evergreen.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith({MockitoExtension.class, EGExtension.class})
public class DeploymentServiceTest extends EGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String EXPECTED_GROUP_NAME = "thinggroup/group1";
    private static final String EXPECTED_ROOT_PACKAGE_NAME = "component1";
    private static final String TEST_CONFIGURATION_ARN = "arn:aws:greengrass:us-east-1:12345678910:configuration"
            + ":thinggroup/group1:1";

    private static final VerificationWithTimeout WAIT_FOUR_SECONDS = timeout(Duration.ofSeconds(4).toMillis());

    @Mock
    private Kernel mockKernel;

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
    @Mock
    private Topics mockGroupPackages;
    @Mock
    private Topics mockComponentsToGroupPackages;
    @Mock
    private EvergreenService mockEvergreenService;
    @Mock
    private DeploymentDirectoryManager deploymentDirectoryManager;

    private Thread deploymentServiceThread;

    DeploymentService deploymentService;
    LinkedBlockingQueue<Deployment> deploymentsQueue;


    @BeforeEach
    public void setup() {
        // initialize Evergreen service specific mocks
        serviceFullName = "DeploymentService";
        initializeMockedConfig();

        lenient().when(stateTopic.getOnce()).thenReturn(State.INSTALLED);

        // Creating the class to be tested
        deploymentService = new DeploymentService(config, mockExecutorService, dependencyResolver, packageManager,
                kernelConfigResolver, deploymentConfigMerger, deploymentStatusKeeper, deploymentDirectoryManager,
                context, mockKernel);
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

    @Nested
    class DeploymentInProgress {
        CompletableFuture<DeploymentResult> mockFuture = spy(new CompletableFuture<>());

        @BeforeEach
        public void setup() throws Exception {
            deploymentService.setPollingFrequency(Duration.ofSeconds(1).toMillis());
            String deploymentDocument
                    = new BufferedReader(new InputStreamReader(
                    getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            deploymentsQueue.put(new Deployment(deploymentDocument,
                    Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_report_succeeded_job_status()
                throws Exception {
            mockGroupToRootPackageMappingStubs();
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            CountDownLatch jobSucceededLatch = new CountDownLatch(1);
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                    jobSucceededLatch.countDown();
                    return null;
                }
            }).when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());

            doNothing().when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());

            startDeploymentServiceInAnotherThread();
            verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());

            verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
            jobSucceededLatch.await(10, TimeUnit.SECONDS);
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());
            ArgumentCaptor<Map<Object, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
            verify(mockGroupPackages).replaceAndWait(mapCaptor.capture());
            Map<Object, Object> groupToRootPackages = mapCaptor.getValue();
            assertThat("Missing group to root package entries",
                    groupToRootPackages != null || !groupToRootPackages.isEmpty());
            assertThat("Expected root package not found",
                    groupToRootPackages.containsKey(EXPECTED_ROOT_PACKAGE_NAME));
            assertThat("Expected package version not found",
                    ((Map<String, String>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME))
                            .get("version").equals("1.0.0"));

            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_set_components_to_groups()
                throws Exception {
            Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
            Topics groupTopics = allGroupTopics.createInteriorChild(EXPECTED_GROUP_NAME);
            Topics componentTopics = groupTopics.createInteriorChild(EXPECTED_ROOT_PACKAGE_NAME);
            componentTopics.createLeafChild(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY).withValue("1.0.0");
            componentTopics.createLeafChild(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN)
                    .withValue(TEST_CONFIGURATION_ARN);
            when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, EXPECTED_GROUP_NAME)).thenReturn(groupTopics);
            Topics componentToGroupsTopics =  mock(Topics.class);
            when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(componentToGroupsTopics);
            when(mockKernel.locate(EXPECTED_ROOT_PACKAGE_NAME)).thenReturn(mockEvergreenService);
            when(mockEvergreenService.getDependencies()).thenReturn(new HashMap<>());
            when(mockEvergreenService.getName()).thenReturn(EXPECTED_ROOT_PACKAGE_NAME);
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);

            doNothing().when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());

            startDeploymentServiceInAnotherThread();
            verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());

            verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(10000))
                    .persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                            eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());
            ArgumentCaptor<Map<Object, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
            verify(componentToGroupsTopics).replaceAndWait(mapCaptor.capture());
            Map<Object, Object> groupToRootPackages = mapCaptor.getValue();
            assertThat(groupToRootPackages, is(IsNull.notNullValue()));
            assertThat(groupToRootPackages.entrySet(), IsNot.not(IsEmptyCollection.empty()));
            assertThat(groupToRootPackages, hasKey(EXPECTED_ROOT_PACKAGE_NAME));
            assertThat((Map<String, Boolean>)groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME), hasKey(TEST_CONFIGURATION_ARN));
        }

        @Test
        public void GIVEN_components_to_groups_mapping_WHEN_get_groups_for_component_THEN_gets_correct_groups() {
            String group1 = "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12";
            String group2 = "arn:aws:greengrass:testRegion:67890:configuration:testGroup:800";
            Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
            Topics componentTopics1 = Topics.of(context, "MockService", allComponentToGroupsTopics);
            Topics componentTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
            Topic groupTopic1 = Topic.of(context, group1, true);
            Topic groupTopic2 = Topic.of(context, group2, true);
            componentTopics1.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
            componentTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic2);
            allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), componentTopics1);
            allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), componentTopics2);
            when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

            Set<String> allGroupConfigs = deploymentService.getAllGroupConfigs();
            assertEquals(2, allGroupConfigs.size());
            assertThat(allGroupConfigs, containsInAnyOrder(group1, group2));

            Set<String> allComponentGroupConfigs = deploymentService.getGroupConfigsForUserComponent("MockService");
            assertEquals(1, allComponentGroupConfigs.size());
            assertThat(allComponentGroupConfigs, containsInAnyOrder(group1));
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_correctly_map_components_to_groups()
                throws Exception {
            Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
            Topics deploymentGroupTopics = Topics.of(context, EXPECTED_GROUP_NAME, allGroupTopics);
            Topic pkgTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
            Topic groupTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                    "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12");
            Map<CaseInsensitiveString, Node> pkgDetails = new HashMap<>();
            pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                    pkgTopic1);
            pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                    groupTopic1);
            Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
            pkgTopics.children.putAll(pkgDetails);
            deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);

            when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, EXPECTED_GROUP_NAME)).thenReturn(deploymentGroupTopics);
            when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(mockComponentsToGroupPackages);
            when(mockKernel.locate(any())).thenReturn(mockEvergreenService);
            when(mockEvergreenService.getName()).thenReturn(EXPECTED_ROOT_PACKAGE_NAME);
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);

            doNothing().when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());

            startDeploymentServiceInAnotherThread();
            verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
            verify(deploymentStatusKeeper, timeout(10000))
                    .persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                            eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());

            verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());
            ArgumentCaptor<Map<Object, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
            verify(mockComponentsToGroupPackages).replaceAndWait(mapCaptor.capture());
            Map<Object, Object> groupToRootPackages = mapCaptor.getValue();
            assertThat(groupToRootPackages, is(IsNull.notNullValue()));
            assertThat(groupToRootPackages.entrySet(), IsNot.not(IsEmptyCollection.empty()));
            assertThat(groupToRootPackages, hasKey(EXPECTED_ROOT_PACKAGE_NAME));
            assertThat((Map<String, Boolean>)groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME),
                    hasKey("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"));
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_completes_with_non_retryable_error_THEN_report_failed_job_status(ExtensionContext context)
                throws Exception {

            CompletableFuture<DeploymentResult> mockFutureWithException = new CompletableFuture<>();
            ignoreExceptionUltimateCauseOfType(context, NonRetryableDeploymentTaskFailureException.class);

            Throwable t = new NonRetryableDeploymentTaskFailureException(null);
            mockFutureWithException.completeExceptionally(t);
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFutureWithException);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());

            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_with_auto_rollback_not_requested_WHEN_deployment_process_fails_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(
                    new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, null));
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_succeeds_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_COMPLETE, null));
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()),
                    any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_fails_THEN_report_failed_job_status()
                throws Exception {
            CompletableFuture<DeploymentResult> mockFuture = new CompletableFuture<>();
            mockFuture
                    .complete(new DeploymentResult(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, null));
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()),
                    any());
            verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_WHEN_deployment_process_fails_with_retry_THEN_retry_job(ExtensionContext context)
                throws Exception {
            mockGroupToRootPackageMappingStubs();
            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
            CompletableFuture<DeploymentResult> mockFutureWithException = new CompletableFuture<>();
            ignoreExceptionUltimateCauseOfType(context, RetryableDeploymentTaskFailureException.class);
            Throwable t = new RetryableDeploymentTaskFailureException(null);
            mockFutureWithException.completeExceptionally(t);
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class)))
                    .thenReturn(mockFutureWithException, mockFutureWithException,
                            mockFuture);
            CountDownLatch jobSuceededLatch = new CountDownLatch(1);
            doAnswer(new Answer() {
                @Override
                public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                    jobSuceededLatch.countDown();
                    return null;
                }
            }).when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());
            doNothing().when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());


            startDeploymentServiceInAnotherThread();
            // Expecting three invocations, once for each retry attempt
            verify(mockExecutorService, WAIT_FOUR_SECONDS.times(3)).submit(any(DefaultDeploymentTask.class));
            InOrder statusOrdering = inOrder(deploymentStatusKeeper);
            statusOrdering.verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
            jobSuceededLatch.await(10, TimeUnit.SECONDS);
            statusOrdering.verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.SUCCEEDED.toString()), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_cancelled_WHEN_waiting_for_safe_time_THEN_then_cancel_deployment()
                throws Exception {
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            when(mockSafeUpdateService.discardPendingUpdateAction(any())).thenReturn(true);
            startDeploymentServiceInAnotherThread();

            // Simulate a cancellation deployment
            deploymentsQueue.put(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

            // Expecting three invocations, once for each retry attempt
            verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
            verify(mockSafeUpdateService, WAIT_FOUR_SECONDS).discardPendingUpdateAction(TEST_CONFIGURATION_ARN);
            verify(mockFuture, WAIT_FOUR_SECONDS).cancel(true);

            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_cancelled_WHEN_already_executing_update_THEN_then_finish_deployment()
                throws Exception {
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            when(mockSafeUpdateService.discardPendingUpdateAction(any())).thenReturn(false);
            startDeploymentServiceInAnotherThread();

            // Simulate a cancellation deployment
            deploymentsQueue.put(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

            // Expecting three invocations, once for each retry attempt
            verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
            verify(mockSafeUpdateService, WAIT_FOUR_SECONDS).discardPendingUpdateAction(TEST_CONFIGURATION_ARN);
            verify(mockFuture, times(0)).cancel(true);
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
            deploymentService.shutdown();
        }

        @Test
        public void GIVEN_deployment_job_cancelled_WHEN_already_finished_deployment_task_THEN_then_do_nothing()
                throws Exception {
            when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
            startDeploymentServiceInAnotherThread();

            CountDownLatch cdl = new CountDownLatch(1);
            Consumer<EvergreenStructuredLogMessage> listener = m -> {
                if (m.getMessage() != null && m.getMessage().equals("Started deployment execution")) {
                    cdl.countDown();
                }
            };
            Slf4jLogAdapter.addGlobalListener(listener);

            // Wait for deployment service to start a new deployment task then simulate finished task
            cdl.await(1, TimeUnit.SECONDS);
            Slf4jLogAdapter.removeGlobalListener(listener);

            // Simulate a cancellation deployment
            deploymentsQueue.put(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

            mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));

            // Expecting three invocations, once for each retry attempt
            verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
            verify(mockSafeUpdateService, times(0)).discardPendingUpdateAction(any());
            verify(mockFuture, times(0)).cancel(true);
            verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                    eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
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

    private void mockGroupToRootPackageMappingStubs() {
        doNothing().when(mockGroupPackages).replaceAndWait(any());
        when(mockGroupPackages.iterator()).thenReturn(mock(Iterator.class));
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, EXPECTED_GROUP_NAME)).thenReturn(mockGroupPackages);
    }
}
