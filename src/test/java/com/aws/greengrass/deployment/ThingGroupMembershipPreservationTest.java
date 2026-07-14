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
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentResult;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import static com.aws.greengrass.deployment.model.Deployment.DeploymentStage.DEFAULT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the Nucleus thing-group-membership preservation bug in
 * {@code DefaultDeploymentTask.getNonTargetGroupToRootPackagesMap()}.
 *
 * <h2>Bug mechanism (passive-absence path)</h2>
 * <p>When computing which non-target groups' root components to preserve across a deployment merge,
 * Nucleus iterates only the groups that already have entries under {@code GroupToRootComponents} in
 * local config. If a group's entry is absent — for any reason (tlog truncation, active deletion by
 * a prior run's {@code cleanupGroupData()}, or any other loss) — the group is invisible to this
 * logic, and its root component is dropped from the merge even when
 * {@code listThingGroupsForDevice} truthfully confirms the device still belongs to that group.
 *
 * <h2>Correct behavior these tests encode</h2>
 * <p>When the cloud membership API ({@code listThingGroupsForDevice}) successfully reports that the
 * device belongs to a group, that group's root components MUST be preserved in the merge's root
 * package set — regardless of whether {@code GroupToRootComponents} already has a local entry for
 * that group. The authoritative cloud membership signal should take precedence over the presence or
 * absence of local bookkeeping.
 *
 * <p>These tests currently FAIL against Nucleus v2.10.2 and mainline (proving the bug exists) and
 * will PASS once the code is fixed to reconcile against actual reported membership.
 *
 * @see GroupToRootComponentsActiveDeletionTest for the related active-deletion path
 */
@ExtendWith({MockitoExtension.class, GGExtension.class})
class ThingGroupMembershipPreservationTest {

    private static final String NON_TARGET_GROUP_NAME = "group1";
    private static final String ROOT_COMPONENT_NAME = "component2";

    private static Context context;
    private final DeploymentDocument deploymentDocument =
            DeploymentDocument.builder().deploymentId("TestLocalDeployment").timestamp(System.currentTimeMillis())
                    .groupName(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME).build();
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

    private Topics groupToRootConfig;
    private Topics groupMembership;
    private DefaultDeploymentTask deploymentTask;

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
        // Simulate a device whose GroupToRootComponents config has NO entry for "group1" — mirrors the
        // state after a tlog truncation or an earlier cleanupGroupData() run actively deleted it.
        // The key insight: this absence should NOT prevent the group's component from being preserved
        // when the cloud membership API confirms the device still belongs to the group.
        groupToRootConfig = Topics.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        groupMembership = Topics.of(context, DeploymentService.GROUP_MEMBERSHIP_TOPICS, null);

        lenient().when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_MEMBERSHIP_TOPICS)))
                .thenReturn(groupMembership);
        lenient().when(mockDeploymentServiceConfig.lookupTopics(eq(DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS)))
                .thenReturn(groupToRootConfig);

        deploymentTask = new DefaultDeploymentTask(mockDependencyResolver, mockComponentManager,
                mockKernelConfigResolver, mockDeploymentConfigMerger, logger,
                new Deployment(deploymentDocument, Deployment.DeploymentType.LOCAL, "localJobId", DEFAULT),
                mockDeploymentServiceConfig, mockExecutorService, deploymentDocumentDownloader, mockThingGroupHelper,
                mockDeviceConfiguration);
    }

    /**
     * REGRESSION TEST (currently FAILS — proving the bug exists).
     *
     * <p>Scenario: GroupToRootComponents has no entry for "group1", but listThingGroupsForDevice
     * SUCCEEDS and truthfully reports the device still belongs to "group1". In correct behavior,
     * the group's root component MUST still be preserved in the merge's root package set.
     *
     * <p>Today's code only iterates groups already present in GroupToRootComponents, so a group
     * whose entry was lost (for any reason) is invisible — its component gets dropped regardless
     * of the API confirming continued membership.
     */
    @Test
    void group_component_preserved_when_cloud_confirms_membership_despite_absent_local_config_entry()
            throws Exception {
        // listThingGroupsForDevice SUCCEEDS and truthfully reports the device still belongs to "group1".
        when(mockThingGroupHelper.listThingGroupsForDevice(1))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));

        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new DeploymentResult(
                        DeploymentResult.DeploymentStatus.SUCCESSFUL, null)));

        ArgumentCaptor<List<String>> rootPackagesCaptor = ArgumentCaptor.forClass(List.class);
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), rootPackagesCaptor.capture(),
                anyLong())).thenReturn(Collections.emptyMap());

        DeploymentResult result = deploymentTask.call();
        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());

        // CORRECT BEHAVIOR: since the cloud API confirmed the device belongs to "group1", its root
        // component MUST be in the preserved root package set, regardless of local config state.
        List<String> rootPackagesUsedForMerge = rootPackagesCaptor.getValue();
        assertTrue(rootPackagesUsedForMerge != null && rootPackagesUsedForMerge.contains(ROOT_COMPONENT_NAME),
                "REGRESSION: group1's root component (component2) was dropped from the merge's root "
                        + "package set even though listThingGroupsForDevice confirmed the device still "
                        + "belongs to group1. The cloud membership signal must take precedence over the "
                        + "absence of a local GroupToRootComponents entry.");

        verify(mockThingGroupHelper).listThingGroupsForDevice(1);
    }

    /**
     * CONTROL TEST (currently PASSES — confirms the happy path works).
     *
     * <p>When GroupToRootComponents DOES have an entry for the group AND listThingGroupsForDevice
     * confirms continued membership, the component is correctly preserved. This isolates the bug
     * to the "entry already absent" precondition.
     */
    @Test
    void group_component_preserved_when_config_entry_exists_and_cloud_confirms_membership()
            throws Exception {
        // Pre-populate GroupToRootComponents with an entry for "group1" (the healthy state).
        groupToRootConfig.lookupTopics(NON_TARGET_GROUP_NAME, ROOT_COMPONENT_NAME)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        when(mockThingGroupHelper.listThingGroupsForDevice(1))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));

        when(mockComponentManager.preparePackages(anyList())).thenReturn(CompletableFuture.completedFuture(null));
        when(mockExecutorService.submit(any(Callable.class)))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(mockDeploymentConfigMerger.mergeInNewConfig(any(), any(), anyLong()))
                .thenReturn(CompletableFuture.completedFuture(new DeploymentResult(
                        DeploymentResult.DeploymentStatus.SUCCESSFUL, null)));

        ArgumentCaptor<List<String>> rootPackagesCaptor = ArgumentCaptor.forClass(List.class);
        when(mockKernelConfigResolver.resolve(anyList(), eq(deploymentDocument), rootPackagesCaptor.capture(),
                anyLong())).thenReturn(Collections.emptyMap());

        deploymentTask.call();

        List<String> rootPackagesUsedForMerge = rootPackagesCaptor.getValue();
        assertTrue(rootPackagesUsedForMerge != null && rootPackagesUsedForMerge.contains(ROOT_COMPONENT_NAME),
                "Control: component2 should be preserved when GroupToRootComponents has an entry for "
                        + "group1 and the device is confirmed to still belong to it.");
    }
}
