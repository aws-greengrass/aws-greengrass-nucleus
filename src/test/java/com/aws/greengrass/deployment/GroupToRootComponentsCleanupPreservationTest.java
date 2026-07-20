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
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.testcommons.testutilities.GGServiceTestUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Collections;
import java.util.concurrent.ExecutorService;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_LAST_DEPLOYMENT_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Regression tests for the active-deletion half of the thing-group state "ratchet":
 * {@code DeploymentService.cleanupGroupData()} deletes any group's {@code GroupToRootComponents} /
 * {@code GroupToLastDeployment} records that are absent from the {@code GroupMembership} snapshot.
 *
 * <p>That snapshot is rebuilt from a fresh cloud fetch by the deployment task's own run. A deployment that
 * completes after a Nucleus restart (bootstrap deployment, e.g. a nucleus upgrade) finishes in a boot session
 * where the snapshot may not have survived — running cleanup there deletes records for groups the device still
 * belongs to, purely because the evidence is gone. Cleanup must only act on membership fetched in the same boot
 * session; the next regular deployment performs the deferred hygiene with fresh data.
 *
 * @see ThingGroupMembershipPreservationTest for the resolution-time (passive-absence) half of the ratchet
 */
@ExtendWith({MockitoExtension.class, GGExtension.class})
class GroupToRootComponentsCleanupPreservationTest extends GGServiceTestUtil {

    private static final String TARGET_GROUP = "thinggroup/targetGroup";
    private static final String OTHER_GROUP = "thinggroup/otherGroup";
    private static final String DEVICE_GROUP = "thing/myThing";
    private static final String TARGET_COMPONENT = "componentA";
    private static final String OTHER_COMPONENT = "componentB";

    @Mock
    private ExecutorService mockExecutorService;
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
    private DeploymentDirectoryManager deploymentDirectoryManager;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private ThingGroupHelper thingGroupHelper;
    @Mock
    private Kernel mockKernel;
    @Mock
    private GreengrassService mockRootService;

    private DeploymentService deploymentService;
    private Context realContext;
    private Topics groupToRootTopics;
    private Topics groupLastDeploymentTopics;
    private Topics groupMembershipTopics;
    private Topics componentsToGroupsTopics;

    @BeforeEach
    void setup() throws Exception {
        serviceFullName = "DeploymentService";
        initializeMockedConfig();
        deploymentService = new DeploymentService(config, mockExecutorService, dependencyResolver, componentManager,
                kernelConfigResolver, deploymentConfigMerger, deploymentStatusKeeper, deploymentDirectoryManager,
                context, mockKernel, deviceConfiguration, thingGroupHelper);

        realContext = new Context();
        groupToRootTopics = Topics.of(realContext, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        groupLastDeploymentTopics = Topics.of(realContext, GROUP_TO_LAST_DEPLOYMENT_TOPICS, null);
        groupMembershipTopics = Topics.of(realContext, DeploymentService.GROUP_MEMBERSHIP_TOPICS, null);
        componentsToGroupsTopics = Topics.of(realContext, COMPONENTS_TO_GROUPS_TOPICS, null);

        lenient().when(config.lookupTopics(eq(GROUP_TO_ROOT_COMPONENTS_TOPICS))).thenReturn(groupToRootTopics);
        lenient().when(config.lookupTopics(eq(GROUP_TO_LAST_DEPLOYMENT_TOPICS))).thenReturn(groupLastDeploymentTopics);
        lenient().when(config.lookupTopics(eq(DeploymentService.GROUP_MEMBERSHIP_TOPICS))).thenReturn(groupMembershipTopics);
        lenient().when(config.lookupTopics(eq(COMPONENTS_TO_GROUPS_TOPICS))).thenReturn(componentsToGroupsTopics);

        lenient().when(mockKernel.locate(any())).thenReturn(mockRootService);
        lenient().when(mockRootService.getName()).thenReturn(TARGET_COMPONENT);
        lenient().when(mockRootService.getDependencies()).thenReturn(Collections.emptyMap());
    }

    @AfterEach
    void cleanup() throws IOException {
        realContext.close();
    }

    private void addGroupRecord(String groupName, String componentName) {
        groupToRootTopics.lookupTopics(groupName, componentName)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupLastDeploymentTopics.lookupTopics(groupName)
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_LAST_DEPLOYMENT_TIMESTAMP_KEY, 1L));
    }

    private DeploymentDocument targetGroupDocument() {
        return DeploymentDocument.builder().deploymentId("deployment1")
                .configurationArn("arn:aws:greengrass:region:account:configuration:" + TARGET_GROUP + ":1")
                .timestamp(System.currentTimeMillis())
                .groupName(TARGET_GROUP)
                .deploymentPackageConfigurationList(Collections.singletonList(
                        new DeploymentPackageConfiguration(TARGET_COMPONENT, true, "1.0.0")))
                .build();
    }

    /**
     * REGRESSION TEST (fails without the fix).
     *
     * <p>A deployment that completes after a Nucleus restart (bootstrap, e.g. a nucleus upgrade) finishes in a
     * boot session where the GroupMembership snapshot rebuilt by its pre-restart task run may not have survived.
     * With the snapshot gone, cleanup has no evidence about membership and must not delete other groups'
     * records; deleting them silently strips those groups' components at the next deployment's resolution.
     */
    @Test
    void GIVEN_membership_snapshot_lost_WHEN_deployment_completes_after_restart_THEN_group_records_are_preserved() {
        addGroupRecord(OTHER_GROUP, OTHER_COMPONENT);
        // GroupMembership is empty: the snapshot did not survive the restart.

        deploymentService.persistGroupToRootComponents(targetGroupDocument(), true);

        assertNotNull(groupToRootTopics.findNode(OTHER_GROUP),
                "REGRESSION: a deployment completing after a restart deleted another group's "
                        + "GroupToRootComponents record based on an empty (lost) GroupMembership snapshot. "
                        + "Cleanup must only act on membership fetched in the same boot session.");
        assertNotNull(groupLastDeploymentTopics.findNode(OTHER_GROUP),
                "GroupToLastDeployment record must also be preserved");
        assertNotNull(groupToRootTopics.findNode(TARGET_GROUP, TARGET_COMPONENT),
                "The target group's record must still be written on completion");
    }

    /**
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): a regular (non-bootstrap) completion whose
     * task run fetched fresh membership still cleans up records of groups the device no longer belongs to.
     */
    @Test
    void GIVEN_fresh_membership_WHEN_regular_deployment_completes_THEN_departed_group_records_are_removed() {
        addGroupRecord(OTHER_GROUP, OTHER_COMPONENT);
        // Fresh membership from this run's task: device belongs only to the target group.
        groupMembershipTopics.createLeafChild(TARGET_GROUP);

        deploymentService.persistGroupToRootComponents(targetGroupDocument(), false);

        assertNull(groupToRootTopics.findNode(OTHER_GROUP),
                "Legitimate cleanup must keep working: the device is confirmed to no longer belong to the "
                        + "group, so its records are removed");
        assertNull(groupLastDeploymentTopics.findNode(OTHER_GROUP),
                "GroupToLastDeployment record of the departed group must also be removed");
        assertNotNull(groupToRootTopics.findNode(TARGET_GROUP, TARGET_COMPONENT),
                "The target group's record must be written on completion");
    }

    /**
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): device-scoped and local-deployment records are
     * never subject to membership-based cleanup.
     */
    @Test
    void GIVEN_empty_membership_WHEN_regular_deployment_completes_THEN_device_and_local_records_are_preserved() {
        addGroupRecord(DEVICE_GROUP, OTHER_COMPONENT);
        addGroupRecord(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME, OTHER_COMPONENT);
        groupMembershipTopics.createLeafChild(TARGET_GROUP);

        deploymentService.persistGroupToRootComponents(targetGroupDocument(), false);

        assertNotNull(groupToRootTopics.findNode(DEVICE_GROUP),
                "Device-scoped records are not membership-governed and must be preserved");
        assertNotNull(groupToRootTopics.findNode(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME),
                "Local-deployment records are not membership-governed and must be preserved");
    }
}
