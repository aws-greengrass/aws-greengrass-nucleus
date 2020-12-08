/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.deployment.model.DeploymentResult.DeploymentStatus;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.impl.GreengrassLogMessage;
import com.aws.greengrass.logging.impl.Slf4jLogAdapter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsNot;
import org.hamcrest.core.IsNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.verification.VerificationWithTimeout;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.LAST_SUCCESSFUL_SHADOW_DEPLOYMENT_ID_TOPIC;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeploymentServiceTest extends GGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String EXPECTED_GROUP_NAME = "thinggroup/group1";
    private static final String EXPECTED_ROOT_PACKAGE_NAME = "component1";
    private static final String TEST_CONFIGURATION_ARN =
            "arn:aws:greengrass:us-east-1:12345678910:configuration" + ":thinggroup/group1:1";

    private static final VerificationWithTimeout WAIT_FOUR_SECONDS = timeout(Duration.ofSeconds(4).toMillis());
    @Mock
    ExecutorService mockExecutorService;
    CompletableFuture<DeploymentResult> mockFuture = spy(new CompletableFuture<>());
    @Mock
    private Kernel mockKernel;
    @Mock
    private DependencyResolver dependencyResolver;
    @Mock
    private ComponentManager componentManager;
    @Mock
    private KernelConfigResolver kernelConfigResolver;
    @Mock
    private DeploymentConfigMerger deploymentConfigMerger;
    @Mock
    private DeploymentStatusKeeper deploymentStatusKeeper;
    @Mock
    private Topics mockComponentsToGroupPackages;
    @Mock
    private GreengrassService mockGreengrassService;
    @Mock
    private DeploymentDirectoryManager deploymentDirectoryManager;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private IotJobsHelper iotJobsHelper;
    private Thread deploymentServiceThread;

    private DeploymentService deploymentService;
    private DeploymentQueue deploymentQueue;


    @BeforeEach
    void setup() {
        // initialize Greengrass service specific mocks
        serviceFullName = "DeploymentService";
        initializeMockedConfig();

        lenient().when(stateTopic.getOnce()).thenReturn(State.INSTALLED);
        Topic pollingFrequency = Topic.of(context, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS, 1L);
        when(deviceConfiguration.getDeploymentPollingFrequencySeconds()).thenReturn(pollingFrequency);
        when(context.get(IotJobsHelper.class)).thenReturn(iotJobsHelper);
        // Creating the class to be tested
        deploymentService = new DeploymentService(config, mockExecutorService, dependencyResolver, componentManager,
                kernelConfigResolver, deploymentConfigMerger, deploymentStatusKeeper, deploymentDirectoryManager,
                context, mockKernel, deviceConfiguration);
        deploymentService.postInject();

        deploymentQueue = new DeploymentQueue();
        deploymentService.setDeploymentsQueue(deploymentQueue);
        Topic lastSuccessfulDeploymentId = Topic.of(context, LAST_SUCCESSFUL_SHADOW_DEPLOYMENT_ID_TOPIC, null);
        lenient().when(config.lookup(LAST_SUCCESSFUL_SHADOW_DEPLOYMENT_ID_TOPIC))
                .thenReturn(lastSuccessfulDeploymentId);
    }

    @AfterEach
    void afterEach() {
        deploymentService.shutdown();
        verify(iotJobsHelper).unsubscribeFromIotJobsTopics();
        if (deploymentServiceThread != null && deploymentServiceThread.isAlive()) {
            deploymentServiceThread.interrupt();
        }
        mockKernel.shutdown();
    }

    private void startDeploymentServiceInAnotherThread() throws InterruptedException {
        CountDownLatch cdl = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
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

    @Test
    void GIVEN_components_to_groups_mapping_WHEN_get_groups_for_component_THEN_gets_correct_groups() {
        String group1 = "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12";
        String group2 = "arn:aws:greengrass:testRegion:67890:configuration:testGroup1:800";
        Topics allComponentToGroupsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics componentTopics1 = Topics.of(context, "MockService", allComponentToGroupsTopics);
        Topics componentTopics2 = Topics.of(context, "MockService2", allComponentToGroupsTopics);
        Topic groupTopic1 = Topic.of(context, group1, "testGroup");
        Topic groupTopic2 = Topic.of(context, group2, "testGroup1");
        componentTopics1.children.put(new CaseInsensitiveString("MockService"), groupTopic1);
        componentTopics2.children.put(new CaseInsensitiveString("MockService2"), groupTopic2);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService"), componentTopics1);
        allComponentToGroupsTopics.children.put(new CaseInsensitiveString("MockService2"), componentTopics2);
        when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(allComponentToGroupsTopics);

        Set<String> allGroupConfigs = deploymentService.getAllGroupNames();
        assertEquals(2, allGroupConfigs.size());
        assertThat(allGroupConfigs, containsInAnyOrder("testGroup", "testGroup1"));

        Set<String> allComponentGroupConfigs = deploymentService.getGroupNamesForUserComponent("MockService");
        assertEquals(1, allComponentGroupConfigs.size());
        assertThat(allComponentGroupConfigs, containsInAnyOrder("testGroup"));
    }

    @Test
    void GIVEN_groups_to_root_components_WHEN_isRootComponent_called_THEN_returns_value_correctly() {
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, EXPECTED_GROUP_NAME, allGroupTopics);
        Topics deploymentGroupTopics2 = Topics.of(context, "AnotherGroup", allGroupTopics);
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

        Topic pkgTopic2 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "2.0.0");
        Topic groupTopic2 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                "arn:aws:greengrass:testRegion:12345:configuration:testGroup2:900");
        Map<CaseInsensitiveString, Node> pkgDetails2 = new HashMap<>();
        pkgDetails2.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                pkgTopic2);
        pkgDetails2.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                groupTopic2);
        Topics pkgTopics2 = Topics.of(context, "AnotherRootComponent", deploymentGroupTopics2);
        pkgTopics2.children.putAll(pkgDetails);
        deploymentGroupTopics2.children.put(new CaseInsensitiveString("AnotherRootComponent"), pkgTopics2);
        allGroupTopics.children.putIfAbsent(new CaseInsensitiveString(EXPECTED_GROUP_NAME), deploymentGroupTopics);
        allGroupTopics.children.putIfAbsent(new CaseInsensitiveString("AnotherGroup"), deploymentGroupTopics2);
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);

        assertTrue(deploymentService.isComponentRoot(EXPECTED_ROOT_PACKAGE_NAME));
        assertTrue(deploymentService.isComponentRoot("AnotherRootComponent"));
        assertFalse(deploymentService.isComponentRoot("RandomComponent"));
    }

    @ParameterizedTest
    @EnumSource(Deployment.DeploymentType.class)
    void GIVEN_deployment_job_WHEN_deployment_process_succeeds_THEN_correctly_map_components_to_groups(
            Deployment.DeploymentType type)
            throws Exception {
        String expectedGroupName = EXPECTED_GROUP_NAME;
        if (type.equals(Deployment.DeploymentType.LOCAL)) {
            expectedGroupName = LOCAL_DEPLOYMENT_GROUP_NAME;
        }
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument, type, TEST_JOB_ID_1));
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

        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, expectedGroupName))
                .thenReturn(deploymentGroupTopics);
        when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(mockComponentsToGroupPackages);

        when(mockKernel.locate(any())).thenReturn(mockGreengrassService);
        when(mockGreengrassService.getName()).thenReturn(EXPECTED_ROOT_PACKAGE_NAME);
        mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);

        doNothing().when(deploymentStatusKeeper).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(type), eq(JobStatus.IN_PROGRESS.toString()), any());

        startDeploymentServiceInAnotherThread();
        verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(type), eq(JobStatus.IN_PROGRESS.toString()), any());
        verify(deploymentStatusKeeper, timeout(10000))
                .persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                        eq(type), eq(JobStatus.SUCCEEDED.toString()), any());

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(type), eq(JobStatus.SUCCEEDED.toString()), any());
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockComponentsToGroupPackages).replaceAndWait(mapCaptor.capture());
        Map<String, Object> groupToRootPackages = mapCaptor.getValue();
        assertThat(groupToRootPackages, is(IsNull.notNullValue()));
        assertThat(groupToRootPackages.entrySet(), IsNot.not(IsEmptyCollection.empty()));
        assertThat(groupToRootPackages, hasKey(EXPECTED_ROOT_PACKAGE_NAME));
        assertThat((Map<String, Boolean>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME),
                hasKey("arn:aws:greengrass:testRegion:12345:configuration:testGroup:12"));
    }

    @Test
    void GIVEN_deployment_job_WHEN_deployment_completes_with_non_retryable_error_THEN_report_failed_job_status(
            ExtensionContext context)
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));
        CompletableFuture<DeploymentResult> mockFutureWithException = new CompletableFuture<>();
        ignoreExceptionUltimateCauseOfType(context, DeploymentTaskFailureException.class);

        Throwable t = new DeploymentTaskFailureException(null);
        mockFutureWithException.completeExceptionally(t);
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFutureWithException);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
    }


    @Test
    void GIVEN_deployment_job_WHEN_deployment_metadata_setup_fails_THEN_report_failed_job_status(
            ExtensionContext context)
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        ignoreExceptionUltimateCauseWithMessage(context, "mock error");

        when(deploymentDirectoryManager.createNewDeploymentDirectory(any()))
                .thenThrow(new IOException("mock error"));
        startDeploymentServiceInAnotherThread();
        ArgumentCaptor<Map<String, String>> statusDetails = ArgumentCaptor.forClass(Map.class);

        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), statusDetails.capture());
        assertEquals("java.io.IOException: mock error", statusDetails.getValue().get("error"));
    }

    @Test
    void GIVEN_deployment_job_with_auto_rollback_not_requested_WHEN_deployment_process_fails_THEN_report_failed_job_status()
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        mockFuture.complete(
                new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
    }

    @Test
    void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_succeeds_THEN_report_failed_job_status()
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        mockFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_COMPLETE, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()),
                any());
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
    }

    @Test
    void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_fails_THEN_report_failed_job_status()
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        mockFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()),
                any());
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.FAILED.toString()), any());
    }

    @Test
    void GIVEN_deployment_job_cancelled_WHEN_waiting_for_safe_time_THEN_then_cancel_deployment()
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        when(updateSystemPolicyService.discardPendingUpdateAction(any())).thenReturn(true);
        startDeploymentServiceInAnotherThread();

        // Simulate a cancellation deployment
        deploymentQueue.offer(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

        // Expecting three invocations, once for each retry attempt
        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
        verify(updateSystemPolicyService, WAIT_FOUR_SECONDS).discardPendingUpdateAction(TEST_CONFIGURATION_ARN);
        verify(mockFuture, WAIT_FOUR_SECONDS).cancel(true);
    }

    @Test
    void GIVEN_deployment_job_cancelled_WHEN_already_executing_update_THEN_then_finish_deployment()
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        when(updateSystemPolicyService.discardPendingUpdateAction(any())).thenReturn(false);
        startDeploymentServiceInAnotherThread();

        // Simulate a cancellation deployment
        deploymentQueue.offer(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

        // Expecting three invocations, once for each retry attempt
        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(updateSystemPolicyService, WAIT_FOUR_SECONDS).discardPendingUpdateAction(TEST_CONFIGURATION_ARN);
        verify(mockFuture, times(0)).cancel(true);
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
    }

    @Test
    void GIVEN_deployment_job_cancelled_WHEN_already_finished_deployment_task_THEN_then_do_nothing()
            throws Exception {
        String deploymentDocument
                = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n"));

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        CountDownLatch cdl = new CountDownLatch(1);
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getMessage() != null && m.getMessage().equals("Started deployment execution")) {
                cdl.countDown();
            }
        };
        Slf4jLogAdapter.addGlobalListener(listener);

        // Wait for deployment service to start a new deployment task then simulate finished task
        cdl.await(1, TimeUnit.SECONDS);
        Slf4jLogAdapter.removeGlobalListener(listener);

        // Simulate a cancellation deployment
        deploymentQueue.offer(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

        mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));

        // Expecting three invocations, once for each retry attempt
        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(updateSystemPolicyService, times(0)).discardPendingUpdateAction(any());
        verify(mockFuture, times(0)).cancel(true);
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(Deployment.DeploymentType.IOT_JOBS), eq(JobStatus.IN_PROGRESS.toString()), any());
    }
}
