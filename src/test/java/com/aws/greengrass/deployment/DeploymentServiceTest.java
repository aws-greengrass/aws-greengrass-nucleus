/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_STACK_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_TYPES_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_QUEUE_TOPIC;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_MEMBERSHIP_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.converter.DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_NAME_KEY;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseWithMessage;
import static java.util.Collections.emptyMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.collection.IsMapContaining.hasKey;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"PMD.LooseCoupling", "PMD.TestClassWithoutTestCases"})
@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeploymentServiceTest extends GGServiceTestUtil {

    private static final String TEST_JOB_ID_1 = "TEST_JOB_1";
    private static final String TEST_UUID = "testDeploymentId";
    private static final String CONFIG_ARN_PLACEHOLDER = "TARGET_CONFIGURATION_ARN";
    private static final String EXPECTED_GROUP_NAME = "thinggroup/group1";
    private static final String EXPECTED_ROOT_PACKAGE_NAME = "component1";
    private static final String TEST_DEPLOYMENT_ID = "testDeploymentId";
    private static final List<String> EXPECTED_ROOT_PACKAGE_LIST = Collections.singletonList("component1");
    private static final Duration TEST_DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(1);

    private static final String TEST_CONFIGURATION_ARN =
            "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1";

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
    @Mock
    private ThingGroupHelper thingGroupHelper;

    private Thread deploymentServiceThread;

    private DeploymentService deploymentService;
    private DeploymentQueue deploymentQueue;


    @BeforeEach
    void setup() {
        // initialize Greengrass service specific mocks
        serviceFullName = "DeploymentService";
        initializeMockedConfig();

        Topic pollingFrequency = Topic.of(context, DeviceConfiguration.DEPLOYMENT_POLLING_FREQUENCY_SECONDS,
                TEST_DEPLOYMENT_POLLING_FREQUENCY.getSeconds());
        when(deviceConfiguration.getDeploymentPollingFrequencySeconds()).thenReturn(pollingFrequency);
        lenient().when(config.lookup(DEPLOYMENT_QUEUE_TOPIC)).thenReturn(Topic.of(context, DEPLOYMENT_QUEUE_TOPIC, null));
        when(context.get(IotJobsHelper.class)).thenReturn(iotJobsHelper);
        // Creating the class to be tested
        deploymentService = new DeploymentService(config, mockExecutorService, dependencyResolver, componentManager,
                kernelConfigResolver, deploymentConfigMerger, deploymentStatusKeeper, deploymentDirectoryManager,
                context, mockKernel, deviceConfiguration, thingGroupHelper);
        deploymentService.postInject();

        deploymentQueue = new DeploymentQueue();
        deploymentService.setDeploymentsQueue(deploymentQueue);
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
        Consumer<GreengrassLogMessage> listener = m -> {
            if (m.getContexts() != null && m.getContexts().containsKey(SERVICE_NAME_KEY) && m.getContexts()
                    .get(SERVICE_NAME_KEY).equalsIgnoreCase(DEPLOYMENT_SERVICE_TOPICS)) {
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
    void GIVEN_groupsToRootComponents_WHEN_setComponentsToGroupsMapping_THEN_get_correct_componentsToGroupsTopics()
            throws Exception{
        // GIVEN
        //   GroupToRootComponents:
        //      LOCAL_DEPLOYMENT:
        //        component1:
        //          groupConfigArn: "asdf"
        //          groupConfigName: "LOCAL_DEPLOYMENT"
        //          version: "1.0.0"
        //        AnotherRootComponent:
        //          groupConfigArn: "asdf"
        //          groupConfigName: "LOCAL_DEPLOYMENT"
        //          version: "2.0.0"
        //      thinggroup/group1:
        //        component1:
        //          groupConfigArn: "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1"
        //          groupConfigName: "thinggroup/group1"
        //          version: "1.0.0"
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics deploymentGroupTopics = Topics.of(context, EXPECTED_GROUP_NAME, allGroupTopics);
        Topics deploymentGroupTopics2 = Topics.of(context, LOCAL_DEPLOYMENT_GROUP_NAME, allGroupTopics);

        // Set up 1st deployment for EXPECTED_GROUP_NAME
        Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
        pkgTopics.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        pkgTopics.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, TEST_CONFIGURATION_ARN));
        pkgTopics.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, EXPECTED_GROUP_NAME));

        deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);
        allGroupTopics.children.putIfAbsent(new CaseInsensitiveString(EXPECTED_GROUP_NAME), deploymentGroupTopics);

        // Set up 2nd deployment for LOCAL_DEPLOYMENT_GROUP_NAME
        Topics pkgTopics2 = Topics.of(context, "AnotherRootComponent", deploymentGroupTopics2);
        pkgTopics2.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "2.0.0"));
        pkgTopics2.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, "asdf"));
        pkgTopics2.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, LOCAL_DEPLOYMENT_GROUP_NAME));
        deploymentGroupTopics2.children.put(new CaseInsensitiveString("AnotherRootComponent"), pkgTopics2);

        Topics pkgTopics3 = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics2);
        pkgTopics3.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        pkgTopics3.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN, "asdf"));
        pkgTopics3.children.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME, LOCAL_DEPLOYMENT_GROUP_NAME));
        deploymentGroupTopics2.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics3);

        allGroupTopics.children.putIfAbsent(new CaseInsensitiveString(LOCAL_DEPLOYMENT_GROUP_NAME),
                deploymentGroupTopics2);

        // Set up mocks
        Topics componentsToGroupsTopics = mock(Topics.class);
        doReturn(componentsToGroupsTopics).when(config).lookupTopics(eq(COMPONENTS_TO_GROUPS_TOPICS));
        GreengrassService expectedRootService = mock(GreengrassService.class);
        GreengrassService anotherRootService = mock(GreengrassService.class);
        GreengrassService dependencyService = mock(GreengrassService.class);
        doReturn(expectedRootService).when(mockKernel).locate(eq(EXPECTED_ROOT_PACKAGE_NAME));
        doReturn(anotherRootService).when(mockKernel).locate(eq("AnotherRootComponent"));
        doReturn(dependencyService).when(mockKernel).locate(eq("Dependency"));
        doReturn(new HashMap<GreengrassService, DependencyType>() {{ put(dependencyService, DependencyType.HARD);}})
                .when(expectedRootService).getDependencies();
        doReturn(new HashMap<GreengrassService, DependencyType>() {{ put(dependencyService, DependencyType.HARD);}})
                .when(anotherRootService).getDependencies();
        doReturn(emptyMap()).when(dependencyService).getDependencies();
        doReturn(EXPECTED_ROOT_PACKAGE_NAME).when(expectedRootService).getName();
        doReturn("AnotherRootComponent").when(anotherRootService).getName();
        doReturn("Dependency").when(dependencyService).getName();

        // WHEN
        deploymentService.setComponentsToGroupsMapping(allGroupTopics);

        // THEN
        //   ComponentToGroups:
        //      component1:
        //        "asdf": "LOCAL_DEPLOYMENT"
        //        "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1": "thinggroup/group1"
        //      AnotherRootComponent:
        //        "asdf": "LOCAL_DEPLOYMENT"
        //      Dependency:
        //        "asdf": "LOCAL_DEPLOYMENT"
        //        "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1": "thinggroup/group1"
        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(componentsToGroupsTopics).replaceAndWait(mapCaptor.capture());
        Map<String, Object> groupToRootPackages = mapCaptor.getValue();

        assertThat(groupToRootPackages, hasKey(EXPECTED_ROOT_PACKAGE_NAME));
        Map<String, String> expectedRootComponentMap =
                (Map<String, String>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME);
        assertEquals(2, expectedRootComponentMap.size());
        assertThat(expectedRootComponentMap, hasEntry(TEST_CONFIGURATION_ARN, EXPECTED_GROUP_NAME));
        assertThat(expectedRootComponentMap, hasEntry("asdf", LOCAL_DEPLOYMENT_GROUP_NAME));

        assertThat(groupToRootPackages, hasKey("AnotherRootComponent"));
        Map<String, String> anotherRootComponentMap = (Map<String, String>) groupToRootPackages.get(
                "AnotherRootComponent");
        assertEquals(1, anotherRootComponentMap.size());
        assertThat(anotherRootComponentMap, hasEntry("asdf", LOCAL_DEPLOYMENT_GROUP_NAME));

        assertThat(groupToRootPackages, hasKey("Dependency"));
        Map<String, String> expectedDepComponentMap =
                (Map<String, String>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME);
        assertEquals(2, expectedDepComponentMap.size());
        assertThat(expectedDepComponentMap, hasEntry(TEST_CONFIGURATION_ARN, EXPECTED_GROUP_NAME));
        assertThat(expectedDepComponentMap, hasEntry("asdf", LOCAL_DEPLOYMENT_GROUP_NAME));
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
        String expectedConfigArn = null;
        String expectedUuid = null;
        if (type.equals(Deployment.DeploymentType.LOCAL)) {
            expectedGroupName = LOCAL_DEPLOYMENT_GROUP_NAME;
        } else {
            expectedConfigArn = TEST_CONFIGURATION_ARN;
            expectedUuid = TEST_UUID;
        }
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument, type, TEST_JOB_ID_1));
        Topics allGroupTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        Topics groupMembershipTopics = Topics.of(context, GROUP_MEMBERSHIP_TOPICS, null);
        groupMembershipTopics.lookup(expectedGroupName);
        Topics deploymentGroupTopics = Topics.of(context, expectedGroupName, allGroupTopics);
        Topic pkgTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0");
        Topic groupTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN,
                "arn:aws:greengrass:testRegion:12345:configuration:testGroup:12");
        Topic groupNameTopic1 = Topic.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME,
                expectedGroupName);
        Map<CaseInsensitiveString, Node> pkgDetails = new HashMap<>();
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY),
                pkgTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_CONFIG_ARN),
                groupTopic1);
        pkgDetails.put(new CaseInsensitiveString(DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_NAME),
                groupNameTopic1);
        Topics pkgTopics = Topics.of(context, EXPECTED_ROOT_PACKAGE_NAME, deploymentGroupTopics);
        pkgTopics.children.putAll(pkgDetails);
        deploymentGroupTopics.children.put(new CaseInsensitiveString(EXPECTED_ROOT_PACKAGE_NAME), pkgTopics);
        allGroupTopics.children.put(new CaseInsensitiveString(expectedGroupName), deploymentGroupTopics);

        when(config.lookupTopics(GROUP_MEMBERSHIP_TOPICS)).thenReturn(groupMembershipTopics);
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        lenient().when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS, expectedGroupName))
                .thenReturn(deploymentGroupTopics);
        when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(mockComponentsToGroupPackages);

        when(mockKernel.locate(any())).thenReturn(mockGreengrassService);
        when(mockGreengrassService.getName()).thenReturn(EXPECTED_ROOT_PACKAGE_NAME);
        mockFuture.complete(new DeploymentResult(DeploymentStatus.SUCCESSFUL, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);

        doNothing().when(deploymentStatusKeeper)
                .persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1), eq(expectedUuid), eq(expectedConfigArn), eq(type),
                        eq(JobStatus.IN_PROGRESS.toString()), any(), any());

        startDeploymentServiceInAnotherThread();
        verify(deploymentStatusKeeper, timeout(1000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(expectedUuid), eq(expectedConfigArn), eq(type), eq(JobStatus.IN_PROGRESS.toString()), any(), any());
        verify(deploymentStatusKeeper, timeout(10000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(expectedUuid), eq(expectedConfigArn), eq(type), eq(JobStatus.SUCCEEDED.toString()), any(), any());

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(expectedUuid), eq(expectedConfigArn), eq(type), eq(JobStatus.SUCCEEDED.toString()), any(), any());
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
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));
        CompletableFuture<DeploymentResult> mockFutureWithException = new CompletableFuture<>();
        ignoreExceptionUltimateCauseOfType(context, DeploymentTaskFailureException.class);

        Throwable t = new DeploymentTaskFailureException("");
        mockFutureWithException.completeExceptionally(t);
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFutureWithException);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.FAILED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
    }


    @Test
    void GIVEN_deployment_job_WHEN_deployment_metadata_setup_fails_THEN_report_failed_job_status(
            ExtensionContext context)
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        ignoreExceptionUltimateCauseWithMessage(context, "mock error");

        when(deploymentDirectoryManager.createNewDeploymentDirectory(any()))
                .thenThrow(new IOException("mock error"));
        startDeploymentServiceInAnotherThread();
        ArgumentCaptor<Map<String, Object>> statusDetails = ArgumentCaptor.forClass(Map.class);

        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.FAILED.toString()), statusDetails.capture(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        assertEquals("Unable to create deployment directory. mock error", statusDetails.getValue().get(DEPLOYMENT_FAILURE_CAUSE_KEY));
        assertListEquals(Arrays.asList("DEPLOYMENT_FAILURE", "IO_ERROR", "IO_WRITE_ERROR"),
                (List<String>) statusDetails.getValue().get(DEPLOYMENT_ERROR_STACK_KEY));
        assertListEquals(Arrays.asList("DEVICE_ERROR"),
                (List<String>) statusDetails.getValue().get(DEPLOYMENT_ERROR_TYPES_KEY));
    }

    @Test
    void GIVEN_deployment_job_with_auto_rollback_not_requested_WHEN_deployment_process_fails_THEN_report_failed_job_status()
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        Topics allGroupTopics = mock(Topics.class);
        Topics groupMembershipTopics = mock(Topics.class);
        Topics deploymentGroupTopics = mock(Topics.class);

        when(allGroupTopics.lookupTopics(EXPECTED_GROUP_NAME)).thenReturn(deploymentGroupTopics);
        when(config.lookupTopics(GROUP_MEMBERSHIP_TOPICS)).thenReturn(groupMembershipTopics);
        when(config.lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS)).thenReturn(allGroupTopics);
        when(config.lookupTopics(COMPONENTS_TO_GROUPS_TOPICS)).thenReturn(mockComponentsToGroupPackages);

        mockFuture.complete(
                new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_NOT_REQUESTED, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.FAILED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));

        ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);
        verify(deploymentGroupTopics).replaceAndWait(mapCaptor.capture());
        Map<String, Object> groupToRootPackages = mapCaptor.getValue();

        assertThat(groupToRootPackages, is(IsNull.notNullValue()));
        assertThat(groupToRootPackages.entrySet(), IsNot.not(IsEmptyCollection.empty()));
        assertThat(groupToRootPackages, hasKey(EXPECTED_ROOT_PACKAGE_NAME));

        Map<String, Object> rootComponentDetails =
                (Map<String, Object>) groupToRootPackages.get(EXPECTED_ROOT_PACKAGE_NAME);
        assertThat(rootComponentDetails, hasEntry("groupConfigArn",
                "arn:aws:greengrass:us-east-1:12345678910:configuration:thinggroup/group1:1"));
        assertThat(rootComponentDetails, hasEntry("groupConfigName", "thinggroup/group1"));
        assertThat(rootComponentDetails, hasEntry("version", "1.0.0"));
    }

    @Test
    void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_succeeds_THEN_report_failed_job_status()
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();


        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        mockFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_ROLLBACK_COMPLETE, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.FAILED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
    }

    @Test
    void GIVEN_deployment_job_with_auto_rollback_requested_WHEN_deployment_fails_and_rollback_fails_THEN_report_failed_job_status()
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        mockFuture.complete(new DeploymentResult(DeploymentStatus.FAILED_UNABLE_TO_ROLLBACK, null));
        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        startDeploymentServiceInAnotherThread();

        verify(mockExecutorService, timeout(1000)).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        verify(deploymentStatusKeeper, timeout(2000)).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.FAILED.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
    }

    @Test
    void GIVEN_deployment_job_cancelled_WHEN_waiting_for_safe_time_THEN_then_cancel_deployment()
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        when(updateSystemPolicyService.discardPendingUpdateAction(any())).thenReturn(true);
        startDeploymentServiceInAnotherThread();

        // Simulate a cancellation deployment
        Thread.sleep(TEST_DEPLOYMENT_POLLING_FREQUENCY.toMillis()); // wait for previous deployment to be polled
        deploymentQueue.offer(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

        // Expecting three invocations, once for each retry attempt
        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
        verify(updateSystemPolicyService, WAIT_FOUR_SECONDS).discardPendingUpdateAction(TEST_DEPLOYMENT_ID);
        verify(mockFuture, WAIT_FOUR_SECONDS).cancel(true);
    }

    @Test
    void GIVEN_deployment_job_cancelled_WHEN_already_executing_update_THEN_then_finish_deployment()
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();

        deploymentQueue.offer(new Deployment(deploymentDocument,
                Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1));

        when(mockExecutorService.submit(any(DefaultDeploymentTask.class))).thenReturn(mockFuture);
        when(updateSystemPolicyService.discardPendingUpdateAction(any())).thenReturn(false);
        startDeploymentServiceInAnotherThread();

        // Simulate a cancellation deployment
        Thread.sleep(TEST_DEPLOYMENT_POLLING_FREQUENCY.toMillis()); // wait for previous deployment to be polled
        deploymentQueue.offer(new Deployment(Deployment.DeploymentType.IOT_JOBS, TEST_JOB_ID_1, true));

        // Expecting three invocations, once for each retry attempt
        verify(mockExecutorService, WAIT_FOUR_SECONDS).submit(any(DefaultDeploymentTask.class));
        verify(updateSystemPolicyService, WAIT_FOUR_SECONDS).discardPendingUpdateAction(TEST_DEPLOYMENT_ID);
        verify(mockFuture, times(0)).cancel(true);
        verify(deploymentStatusKeeper, WAIT_FOUR_SECONDS).persistAndPublishDeploymentStatus(eq(TEST_JOB_ID_1),
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
    }

    @Test
    void GIVEN_deployment_job_cancelled_WHEN_already_finished_deployment_task_THEN_then_do_nothing()
            throws Exception {
        String deploymentDocument = getTestDeploymentDocument();

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
                eq(TEST_UUID), eq(TEST_CONFIGURATION_ARN), eq(Deployment.DeploymentType.IOT_JOBS),
                eq(JobStatus.IN_PROGRESS.toString()), any(), eq(EXPECTED_ROOT_PACKAGE_LIST));
    }

    String getTestDeploymentDocument() {
        return new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("TestDeploymentDocument.json"), StandardCharsets.UTF_8))
                .lines()
                .collect(Collectors.joining("\n")).replace(CONFIG_ARN_PLACEHOLDER, TEST_CONFIGURATION_ARN);
    }

    private void assertListEquals(List<String> first, List<String> second) {
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i), second.get(i));
        }
    }
}
