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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression tests for thing-group membership handling in
 * {@code DefaultDeploymentTask.getNonTargetGroupToRootPackagesMap()}.
 *
 * <h2>The defect</h2>
 * <p>{@code ThingGroupHelper.listThingGroupsForDevice()} returns {@code Optional.empty()} when the device is
 * not configured to talk to the cloud (no lookup is even attempted) — membership is <em>unknown</em>. The task
 * coerced that unknown to an empty set, i.e. an authoritative "member of no thing groups". One local deployment
 * processed in that state drops every recorded thing group's components from the merge (removing them from the
 * device) and rebuilds the {@code GroupMembership} snapshot empty, which lets the post-deployment cleanup
 * delete every thing group's records. This requires no prior loss of state — a device whose cloud provisioning
 * config is missing or invalid (including lost or corrupted config) running a local deployment is sufficient.
 *
 * <h2>The contract these tests encode</h2>
 * <p>Unknown membership must be handled like the existing fetch-failure fallbacks: fall back to the membership
 * implied by the persisted group records. Only an authoritative response may change what is preserved — a
 * successful lookup returning an empty set (the device really belongs to no thing groups) still removes.
 */
@ExtendWith({MockitoExtension.class, GGExtension.class})
class ThingGroupMembershipPreservationTest {

    private static final String GROUP_NAME = "thinggroup/group1";
    private static final String ROOT_COMPONENT_NAME = "component2";

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

        // A healthy record from a prior deployment: "group1" wants "component2".
        groupToRootConfig.lookupTopics(GROUP_NAME, ROOT_COMPONENT_NAME)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
    }

    private DefaultDeploymentTask createLocalDeploymentTask(DeploymentDocument document) {
        return new DefaultDeploymentTask(mockDependencyResolver, mockComponentManager, mockKernelConfigResolver,
                mockDeploymentConfigMerger, logger,
                new Deployment(document, Deployment.DeploymentType.LOCAL, "localJobId", DEFAULT),
                mockDeploymentServiceConfig, mockExecutorService, deploymentDocumentDownloader, mockThingGroupHelper,
                mockDeviceConfiguration);
    }

    private DeploymentDocument localDeploymentDocument() {
        return DeploymentDocument.builder().deploymentId("TestLocalDeployment")
                .timestamp(System.currentTimeMillis())
                .groupName(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME).build();
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

    /**
     * REGRESSION TEST (fails without the fix): unknown membership must not mean "member of no groups".
     *
     * <p>The membership lookup reports unknown ({@code Optional.empty()} — the device is not configured to talk
     * to the cloud, so no lookup was attempted). The healthy group record must still preserve its component,
     * exactly as the fetch-failure fallback paths already do, and the rebuilt {@code GroupMembership} snapshot
     * must still contain the group so the post-deployment cleanup does not delete its records.
     */
    @Test
    void GIVEN_membership_unknown_WHEN_local_deployment_THEN_recorded_group_components_are_preserved()
            throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenReturn(Optional.empty());
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        DeploymentResult result = createLocalDeploymentTask(document).call();

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertTrue(rootPackages != null && rootPackages.contains(ROOT_COMPONENT_NAME),
                "REGRESSION: membership 'unknown' was treated as an authoritative 'member of no groups', "
                        + "dropping a healthy group record's component from the merge. Unknown membership must "
                        + "fall back to the persisted membership info instead.");
        assertNotNull(groupMembership.find(GROUP_NAME),
                "GroupMembership must be rebuilt from persisted info when membership is unknown, so that "
                        + "cleanupGroupData does not delete the group's records");
    }

    /**
     * CONTROL TEST (passes with and without the fix): an authoritative lookup confirming membership preserves
     * the recorded group's component.
     */
    @Test
    void GIVEN_membership_confirmed_WHEN_local_deployment_THEN_recorded_group_components_are_preserved()
            throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(GROUP_NAME)));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        createLocalDeploymentTask(document).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertTrue(rootPackages != null && rootPackages.contains(ROOT_COMPONENT_NAME),
                "Control: component must be preserved when membership is confirmed");
    }

    /**
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): an authoritative lookup returning an empty
     * set is not "unknown" — the device really belongs to no thing groups, and the recorded group's component
     * is removed. Pins the distinction between {@code Optional.empty()} and {@code Optional.of(emptySet)},
     * guarding the fix against over-preservation.
     */
    @Test
    void GIVEN_authoritative_empty_membership_WHEN_local_deployment_THEN_recorded_group_components_are_removed()
            throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.emptySet()));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        createLocalDeploymentTask(document).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertFalse(rootPackages.contains(ROOT_COMPONENT_NAME),
                "An authoritative empty membership response must keep removing: the device genuinely belongs "
                        + "to no thing groups");
    }
}
