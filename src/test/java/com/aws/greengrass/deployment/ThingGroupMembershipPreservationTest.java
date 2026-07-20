/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.VERSION_CONFIG_KEY;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the thing-group state "ratchet" in
 * {@code DefaultDeploymentTask.getNonTargetGroupToRootPackagesMap()}.
 *
 * <h2>The defect</h2>
 * <p>The set of root components a deployment keeps is computed exclusively from local bookkeeping
 * ({@code GroupToRootComponents}). A running root component whose group record is absent — for any reason,
 * including loss of persisted state — is invisible to that computation and is silently removed by the merge,
 * even when the cloud membership API truthfully confirms the device still belongs to the group. Destruction of
 * group state is automatic; restoration requires a manual cloud redeployment.
 *
 * <h2>The contract these tests encode</h2>
 * <p>A currently-running root component was put there by a previous deployment. Removing it requires positive
 * evidence, exactly one of:
 * <ul>
 * <li>a local deployment explicitly lists it for removal;</li>
 * <li>a cloud deployment targets a group the component is attributed to and its (authoritative) document no
 * longer includes the component;</li>
 * <li>freshly fetched cloud membership shows the device no longer belongs to any group the component is
 * attributed to.</li>
 * </ul>
 * Absence of local bookkeeping is never evidence. Unknown membership is never treated as "member of no groups".
 */
@ExtendWith({MockitoExtension.class, GGExtension.class})
class ThingGroupMembershipPreservationTest {

    private static final String NON_TARGET_GROUP_NAME = "thinggroup/group1";
    private static final String RUNNING_COMPONENT_NAME = "component2";
    private static final String RUNNING_COMPONENT_VERSION = "1.0.0";
    private static final String CLOUD_TARGET_GROUP_NAME = "thinggroup/targetGroup";
    private static final String CLOUD_TARGET_ROOT_COMPONENT = "componentA";

    private static Context context;
    private final Logger logger = LogManager.getLogger("thing-group-membership-preservation");

    @Mock
    private DependencyResolver mockDependencyResolver;
    @Mock
    private ComponentManager mockComponentManager;
    @Mock
    private KernelConfigResolver mockKernelConfigResolver;
    @Mock
    private DeploymentConfigMerger mockDeploymentConfigMerger;
    @Mock
    private ExecutorService mockExecutorService;
    @Mock
    private DeploymentDocumentDownloader deploymentDocumentDownloader;
    @Mock
    private ThingGroupHelper mockThingGroupHelper;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Mock
    private Topics mockDeploymentServiceConfig;
    @Mock
    private Kernel mockKernel;
    @Mock
    private GreengrassService mockMainService;
    @Mock
    private GreengrassService mockRunningComponent;

    private Topics groupToRootConfig;
    private Topics groupMembership;
    private ArgumentCaptor<List<String>> rootPackagesCaptor;

    @BeforeAll
    static void setupContext() {
        context = new Context();
    }

    @AfterAll
    static void cleanContext() throws IOException {
        context.close();
    }

    @BeforeEach
    void setup() {
        groupToRootConfig = Topics.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        groupMembership = Topics.of(context, DeploymentService.GROUP_MEMBERSHIP_TOPICS, null);

        lenient().when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_MEMBERSHIP_TOPICS)))
                .thenReturn(groupMembership);
        lenient().when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS)))
                .thenReturn(groupToRootConfig);

        // "component2" is a currently-running root component: a non-builtin direct dependency of main,
        // running at version 1.0.0. This is the runtime truth that survives bookkeeping loss.
        lenient().when(mockKernel.getMain()).thenReturn(mockMainService);
        Map<GreengrassService, DependencyType> mainDeps = new HashMap<>();
        mainDeps.put(mockRunningComponent, DependencyType.HARD);
        lenient().when(mockMainService.getDependencies()).thenReturn(mainDeps);
        lenient().when(mockRunningComponent.getServiceName()).thenReturn(RUNNING_COMPONENT_NAME);
        lenient().when(mockRunningComponent.isBuiltin()).thenReturn(false);
        Topics runningComponentConfig = Topics.of(context, RUNNING_COMPONENT_NAME, null);
        runningComponentConfig.lookup(VERSION_CONFIG_KEY).withValue(RUNNING_COMPONENT_VERSION);
        lenient().when(mockRunningComponent.getServiceConfig()).thenReturn(runningComponentConfig);
    }

    private DefaultDeploymentTask createTask(Deployment deployment) {
        return new DefaultDeploymentTask(mockDependencyResolver, mockComponentManager, mockKernelConfigResolver,
                mockDeploymentConfigMerger, logger, deployment, mockDeploymentServiceConfig, mockExecutorService,
                deploymentDocumentDownloader, mockThingGroupHelper, mockDeviceConfiguration, mockKernel);
    }

    private Deployment localDeployment(DeploymentDocument document) {
        return new Deployment(document, Deployment.DeploymentType.LOCAL, "localJobId", DEFAULT);
    }

    private DeploymentDocument localDeploymentDocument() {
        return DeploymentDocument.builder().deploymentId("TestLocalDeployment")
                .timestamp(System.currentTimeMillis())
                .groupName(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME).build();
    }

    private DeploymentDocument cloudDeploymentDocument() {
        return DeploymentDocument.builder().deploymentId("TestCloudDeployment")
                .timestamp(System.currentTimeMillis())
                .groupName(CLOUD_TARGET_GROUP_NAME)
                .deploymentPackageConfigurationList(Collections.singletonList(
                        new DeploymentPackageConfiguration(CLOUD_TARGET_ROOT_COMPONENT, true, "1.0.0")))
                .build();
    }

    private void mockSuccessfulPipeline(DeploymentDocument document) throws Exception {
        lenient().when(mockComponentManager.preparePackages(anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        lenient().when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        lenient().when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(
                        new DeploymentResult(DeploymentResult.DeploymentStatus.SUCCESSFUL, null)));
        rootPackagesCaptor = ArgumentCaptor.forClass(List.class);
        lenient().when(mockKernelConfigResolver.resolve(anyList(), eq(document), rootPackagesCaptor.capture(),
                anyLong())).thenReturn(Collections.emptyMap());
    }

    private void attributeRunningComponentToGroup(String groupName) {
        Topics attribution = Topics.of(context, RUNNING_COMPONENT_NAME, null);
        attribution.lookup("deployment-arn-1").withValue(groupName);
        lenient().when(mockDeploymentServiceConfig.findNode(eq(DeploymentService.COMPONENTS_TO_GROUPS_TOPICS),
                eq(RUNNING_COMPONENT_NAME))).thenReturn(attribution);
    }

    /**
     * REGRESSION TEST (fails without the fix) — the V228 incident shape.
     *
     * <p>All group bookkeeping is lost. The component is still running as a root, and the cloud truthfully
     * confirms the device still belongs to its group. A local deployment (which is additive/mutative by
     * contract) must not remove the running component.
     */
    @Test
    void local_deployment_preserves_running_root_component_when_group_record_is_missing() throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        DeploymentResult result = createTask(localDeployment(document)).call();

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertTrue(rootPackages != null && rootPackages.contains(RUNNING_COMPONENT_NAME),
                "REGRESSION: a running root component was dropped from the merge's root package set solely "
                        + "because its local GroupToRootComponents record is missing. Absence of bookkeeping is "
                        + "not evidence of removal; running roots must be preserved until there is positive "
                        + "evidence the component should be removed.");
        verify(mockThingGroupHelper).listThingGroupsForDevice(1);
    }

    /**
     * REGRESSION TEST (fails without the fix) — the multi-group collateral-removal shape.
     *
     * <p>Device belongs to two thing groups and both groups' bookkeeping is lost. A cloud deployment targeting
     * only the first group must not remove the second group's still-running component: membership confirms the
     * device is still in that group, and nothing attributes the component to the target group.
     */
    @Test
    void cloud_deployment_to_one_group_preserves_running_component_of_another_group_with_lost_record()
            throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenReturn(Optional.of(
                new java.util.HashSet<>(java.util.Arrays.asList(CLOUD_TARGET_GROUP_NAME, NON_TARGET_GROUP_NAME))));
        DeploymentDocument document = cloudDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(new Deployment(document, Deployment.DeploymentType.IOT_JOBS, "cloudJobId", DEFAULT)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertTrue(rootPackages.contains(CLOUD_TARGET_ROOT_COMPONENT),
                "Target group's root component from the deployment document must be present");
        assertTrue(rootPackages.contains(RUNNING_COMPONENT_NAME),
                "REGRESSION: a running root component belonging to a non-target group was removed by a cloud "
                        + "deployment because the group's local record was lost. The component is unattributed "
                        + "and the device is still a member of its group; there is no positive evidence for "
                        + "removal, so it must be preserved.");
    }

    /**
     * REGRESSION TEST (fails without the fix) — unknown membership must not mean "member of nothing".
     *
     * <p>The membership API reports "unknown" (device not configured to talk to the cloud). The healthy group
     * record must still preserve its component, exactly as the fetch-failure fallback paths already do.
     */
    @Test
    void unknown_membership_is_not_treated_as_empty_membership() throws Exception {
        groupToRootConfig.lookupTopics(NON_TARGET_GROUP_NAME, RUNNING_COMPONENT_NAME)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenReturn(Optional.empty());
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(localDeployment(document)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertTrue(rootPackages != null && rootPackages.contains(RUNNING_COMPONENT_NAME),
                "REGRESSION: membership 'unknown' was treated as an authoritative 'member of no groups', "
                        + "dropping a healthy group record's component from the merge. Unknown membership must "
                        + "fall back to the persisted membership info instead.");
        assertNotNull(groupMembership.find(NON_TARGET_GROUP_NAME),
                "GroupMembership must be rebuilt from persisted info when membership is unknown, so that "
                        + "cleanupGroupData does not delete the group's records");
    }

    /**
     * CONTROL TEST (passes with and without the fix): healthy bookkeeping and confirmed membership preserve
     * the component. Isolates the defect to the record-absent cases above.
     */
    @Test
    void group_component_preserved_when_config_entry_exists_and_cloud_confirms_membership() throws Exception {
        groupToRootConfig.lookupTopics(NON_TARGET_GROUP_NAME, RUNNING_COMPONENT_NAME)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(localDeployment(document)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertTrue(rootPackages != null && rootPackages.contains(RUNNING_COMPONENT_NAME),
                "Control: component must be preserved when its group record exists and membership is confirmed");
    }

    /**
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): when freshly fetched membership shows the
     * device no longer belongs to the only group a running component is attributed to, the component is
     * removed. Positive evidence exists; preservation must not block it.
     */
    @Test
    void running_component_removed_when_authoritative_membership_shows_device_left_its_group() throws Exception {
        groupToRootConfig.lookupTopics(NON_TARGET_GROUP_NAME, RUNNING_COMPONENT_NAME)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        attributeRunningComponentToGroup(NON_TARGET_GROUP_NAME);
        // Authoritative response: the device belongs to no thing groups anymore.
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.emptySet()));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(localDeployment(document)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertFalse(rootPackages.contains(RUNNING_COMPONENT_NAME),
                "Legitimate group-removal flow must keep working: authoritative membership shows the device "
                        + "left the component's group, so the component must be removed from the root set");
    }

    /**
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): a cloud deployment's document is authoritative
     * for its target group. A running component attributed to the target group that the document no longer
     * includes must be removed, not preserved.
     */
    @Test
    void cloud_deployment_removes_running_component_dropped_from_its_target_group_document() throws Exception {
        attributeRunningComponentToGroup(CLOUD_TARGET_GROUP_NAME);
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(CLOUD_TARGET_GROUP_NAME)));
        DeploymentDocument document = cloudDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(new Deployment(document, Deployment.DeploymentType.IOT_JOBS, "cloudJobId", DEFAULT)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertFalse(rootPackages.contains(RUNNING_COMPONENT_NAME),
                "A cloud deployment document is the authoritative root set for its target group; a component "
                        + "attributed to the target group and absent from the document must be removed");
    }

    /**
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): explicit removal in a local deployment request
     * is always honored, even when the bookkeeping that would normally reflect the component is lost.
     */
    @Test
    void local_deployment_explicit_removal_honored_even_when_records_are_lost() throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        String rawLocalRequest = "{\"requestId\":\"localJobId\",\"componentsToRemove\":[\""
                + RUNNING_COMPONENT_NAME + "\"]}";
        Deployment deployment = new Deployment(rawLocalRequest, Deployment.DeploymentType.LOCAL, "localJobId");
        deployment.setDeploymentDocumentObj(document);

        createTask(deployment).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertFalse(rootPackages.contains(RUNNING_COMPONENT_NAME),
                "A component explicitly listed in rootComponentsToRemove must not be preserved, regardless of "
                        + "bookkeeping state");
    }
}
