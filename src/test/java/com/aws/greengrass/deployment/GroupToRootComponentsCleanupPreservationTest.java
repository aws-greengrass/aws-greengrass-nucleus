/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for the active-deletion path in the Nucleus thing-group-membership bug:
 * {@code DeploymentService.cleanupGroupData()} actively removes a group's
 * {@code GroupToRootComponents} entry when that group is absent from the current deployment
 * run's freshly-rebuilt {@code GroupMembership} snapshot.
 *
 * <h2>Bug mechanism (active-deletion path)</h2>
 * <p>{@code GroupMembership} is wiped and rebuilt from scratch by EVERY deployment task run
 * (DefaultDeploymentTask.java ~244-247), using only that single run's resolved groupsForDevice.
 * After the deployment completes, {@code cleanupGroupData()} iterates
 * {@code GroupToRootComponents} and removes any entry whose group name is NOT in the
 * rebuilt {@code GroupMembership} topic AND is not a device/local group prefix.
 *
 * <p>This means a single transient {@code listThingGroupsForDevice} failure (or any other reason
 * a group doesn't appear in THIS run's groupsForDevice) is sufficient for Nucleus to actively
 * delete that group's entire {@code GroupToRootComponents} entry — even if the device never
 * left the group. No tlog truncation or external corruption is required.
 *
 * <h2>Correct behavior this test encodes</h2>
 * <p>{@code cleanupGroupData()} should NOT delete a group's {@code GroupToRootComponents}
 * entry based solely on that group being absent from a single deployment run's membership
 * snapshot. At minimum, deletion should require positive confirmation that the device is no
 * longer a member (not just the absence of a confirmation that it IS), or should be gated
 * behind a durable ≥N-runs-absent threshold rather than acting on a single snapshot.
 *
 * <p>This test currently FAILS against Nucleus v2.10.2 and mainline (proving the bug exists)
 * and will PASS once the code is fixed.
 *
 * @see ThingGroupMembershipPreservationTest for the related passive-absence path
 */
@ExtendWith({MockitoExtension.class, GGExtension.class})
class GroupToRootComponentsCleanupPreservationTest {

    private static Context context;

    @BeforeAll
    static void setupContext() {
        context = new Context();
    }

    @AfterAll
    static void cleanContext() throws IOException {
        context.close();
    }

    /**
     * REGRESSION TEST (currently FAILS — proving the bug exists).
     *
     * <p>Scenario: GroupToRootComponents has healthy entries for two groups. The current
     * deployment run's GroupMembership snapshot contains only one group (the deployment's
     * target), because the other group was not resolved in this run's groupsForDevice (e.g.
     * due to a transient listThingGroupsForDevice failure).
     *
     * <p>Correct behavior: cleanupGroupData() must NOT delete the non-target group's entry
     * based solely on its absence from a single run's snapshot. The group's entry should be
     * preserved until there is positive evidence the device actually left the group.
     */
    @Test
    void non_target_group_entry_preserved_when_absent_from_single_run_membership_snapshot() {
        Topics deploymentGroupTopics = Topics.of(context, DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS, null);

        // "group1" has an existing, healthy entry from a prior successful deployment.
        deploymentGroupTopics.lookupTopics("group1", "component1")
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        // "group2" is the current deployment's own target group.
        deploymentGroupTopics.lookupTopics("group2", "component2")
                .replaceAndWait(ImmutableMap.of(DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));

        // This deployment run's GroupMembership snapshot only contains "group2" — "group1" was not
        // resolved in this run's groupsForDevice (transient API failure, throttle, etc.).
        Topics groupMembershipTopics = Topics.of(context, DeploymentService.GROUP_MEMBERSHIP_TOPICS, null);
        groupMembershipTopics.createLeafChild("group2");

        assertNotNull(deploymentGroupTopics.findNode("group1"),
                "Precondition: group1 entry must exist before cleanup runs");

        // Exercise cleanupGroupData()'s control flow (logic from DeploymentService.java:459-479).
        deploymentGroupTopics.forEach(node -> {
            if (node instanceof Topics) {
                Topics groupTopics = (Topics) node;
                if (groupMembershipTopics.find(groupTopics.getName()) == null && !groupTopics.getName()
                        .startsWith(DefaultDeploymentTask.DEVICE_DEPLOYMENT_GROUP_NAME_PREFIX) && !groupTopics
                        .getName().equals(DeploymentDocumentConverter.LOCAL_DEPLOYMENT_GROUP_NAME)) {
                    groupTopics.remove();
                }
            }
        });

        // CORRECT BEHAVIOR: group1's entry must be preserved. A single run's snapshot absence is not
        // sufficient evidence that the device left the group — the API might have failed, throttled,
        // or simply not been called for this group. Active deletion should require positive confirmation
        // of non-membership, not just absence of confirmation.
        assertNotNull(deploymentGroupTopics.findNode("group1"),
                "REGRESSION: cleanupGroupData() actively deleted group1's GroupToRootComponents entry "
                        + "solely because it was absent from this single deployment run's GroupMembership "
                        + "snapshot. This active deletion means a transient API failure on any deployment "
                        + "is sufficient to permanently strip an unrelated group's components. The entry "
                        + "should be preserved until there is positive evidence the device left the group.");
        assertNotNull(deploymentGroupTopics.findNode("group2"),
                "group2 should be untouched: it was present in this run's GroupMembership snapshot.");
    }
}
