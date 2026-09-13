/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICE_DEPENDENCIES_NAMESPACE_TOPIC;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;

/**
 * CHARACTERIZATION TESTS — these demonstrate a confirmed bug (V2284253627) and are expected
 * to FAIL once a fix lands. They are not regression tests for correct behavior; they are
 * executable proof that the defect exists in v2.10.2.
 *
 * Bug summary: The isDefaultDependency flag on builtin services (DeploymentService,
 * FleetStatusService, TelemetryAgent, UpdateSystemPolicyService) is permanently erased
 * by the first deployment in a boot session that includes them in main/dependencies.
 * setupDependencies() unconditionally calls addOrUpdateDependency(service, type, false),
 * overwriting the true flag set at boot. After erasure, any subsequent write to
 * main/dependencies that omits builtins (such as a rollback snapshot replay restoring
 * the pre-deployment topic value) evicts them from the in-memory dependency map.
 * They remain running but are invisible to orderedDependencies() and therefore to FSS.
 *
 * Incident trigger: deployment 71b36fb0 to PlugAndPlayGroup-IOTAutomation-prod-fe
 * FAILED_ROLLBACK_COMPLETE at 2026-06-18T09:39:53Z. The forward-merge erased the flag;
 * the rollback replay wrote main/dependencies from the snapshot (Nucleus-only), evicting
 * the 4 builtins. They never reappeared in any FSS CADENCE message for the remaining
 * 23 days of uptime.
 *
 * Related: PR #1819 (cleanupGroupData regression tests), PR #1820 (LogManager churn fix).
 */
@ExtendWith({GGExtension.class, MockitoExtension.class})
class BuiltinDependencyFlagTest {

    @Mock
    private Kernel kernel;
    private Context context;
    private Configuration configuration;
    private ExecutorService executorService;
    private GreengrassService mainService;
    private GreengrassService builtinService; // simulates DeploymentService/FSS/TelemetryAgent/UpdateSysPol
    private GreengrassService componentA;     // simulates a deployed component (e.g. from IOTAutomation group)
    private GreengrassService componentB;     // simulates a deployed component (e.g. from MHEAutomation group)

    @BeforeEach
    void beforeEach() throws IOException, URISyntaxException, ServiceLoadException {
        Path configPath = Paths.get(this.getClass().getResource("services.yaml").toURI());
        context = spy(new Context());
        context.put(Kernel.class, kernel);
        executorService = Executors.newFixedThreadPool(1);
        context.put(Executor.class, executorService);
        configuration = new Configuration(context);
        configuration.read(configPath);
        Topics root = configuration.getRoot();

        // B = builtin service, C = user component A, D = user component B
        builtinService = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "B"));
        componentA = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "C"));
        componentB = new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "D"));

        lenient().when(kernel.locateIgnoreError("B")).thenReturn(builtinService);
        lenient().when(kernel.locateIgnoreError("C")).thenReturn(componentA);
        lenient().when(kernel.locateIgnoreError("D")).thenReturn(componentB);
        lenient().when(kernel.locateIgnoreError("A")).thenReturn(componentA);

        // Use service E (no pre-configured dependencies in services.yaml) as our test subject
        mainService = spy(new GreengrassService(root.findTopics(SERVICES_NAMESPACE_TOPIC, "E")));
    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
        executorService.shutdownNow();
    }

    @Test
    @DisplayName("Baseline: isDefaultDependency=true protects builtin from removal when absent from dependency list")
    void GIVEN_builtin_with_default_flag_WHEN_not_in_dependency_list_THEN_builtin_is_protected() throws Exception {
        // Boot injection: builtin added with isDefault=true (same as KernelLifecycle.launch)
        mainService.addOrUpdateDependency(builtinService, DependencyType.HARD, true);
        assertTrue(mainService.dependencies.get(builtinService).isDefaultDependency,
                "Precondition: flag must be true");

        // A dependency-list write that omits the builtin
        Topic depTopic = mainService.getConfig().find(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        depTopic.withValue(Arrays.asList("C", "D"));
        context.runOnPublishQueueAndWait(() -> {});

        // Builtin survives because isDefaultDependency=true prevents removal in setupDependencies()
        assertNotNull(mainService.dependencies.get(builtinService),
                "Builtin with isDefault=true must NOT be removed when absent from dependency list");
    }

    @Test
    @DisplayName("Bug: setupDependencies() erases isDefaultDependency flag when builtin is in the written list")
    void GIVEN_builtin_with_default_flag_WHEN_setupDependencies_includes_builtin_THEN_flag_erased() throws Exception {
        // Boot injection
        mainService.addOrUpdateDependency(builtinService, DependencyType.HARD, true);
        assertTrue(mainService.dependencies.get(builtinService).isDefaultDependency,
                "Precondition: flag must be true after boot injection");

        // Deployment forward-merge writes main/dependencies including the builtin.
        // setupDependencies() calls addOrUpdateDependency(builtin, HARD, false) — always false.
        Topic depTopic = mainService.getConfig().find(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        depTopic.withValue(Arrays.asList("B:HARD", "C"));
        context.runOnPublishQueueAndWait(() -> {});

        // The flag is now erased. This is the bug enabler.
        assertFalse(mainService.dependencies.get(builtinService).isDefaultDependency,
                "BUG: setupDependencies() overwrites isDefaultDependency to false when the builtin "
                + "appears in the dependency list. The protection is permanently erased for this session.");
    }

    @Test
    @DisplayName("Bug: rollback snapshot replay evicts builtins after flag erasure (incident reproduction)")
    void GIVEN_flag_erased_by_forward_merge_WHEN_rollback_restores_pre_deployment_deps_THEN_builtins_evicted()
            throws Exception {
        // === Simulates the exact incident sequence on 2026-06-18 for device spider-24fbe3d7c2ee-dej3 ===

        // Phase 1: Boot — builtins injected with protection (KernelLifecycle.launch)
        mainService.addOrUpdateDependency(builtinService, DependencyType.HARD, true);
        assertTrue(mainService.dependencies.get(builtinService).isDefaultDependency);

        // Phase 2: IOTAutomation deployment forward-merge at 09:39.
        // The resolved newConfig includes builtins in main/dependencies (KernelConfigResolver.getMainConfig
        // always carries forward builtins present in the map). This writes the topic, triggering
        // setupDependencies() which re-adds them with isDefault=false.
        Topic depTopic = mainService.getConfig().find(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        depTopic.withValue(Arrays.asList("B:HARD", "C"));
        context.runOnPublishQueueAndWait(() -> {});

        // Verify flag is erased (precondition for Phase 3)
        assertFalse(mainService.dependencies.get(builtinService).isDefaultDependency,
                "Flag must be erased by forward-merge before rollback can cause eviction");

        // Phase 3: Deployment FAILS → rollback.
        // DeploymentActivator.rollbackConfig() calls ConfigurationReader.updateFromTLog(snapshotPath,
        // forceTimestamp=true, null, createRollbackMergeBehavior()).
        // The snapshot was taken BEFORE the deployment added builtins to main/dependencies.
        // Under createRollbackMergeBehavior (services.*: REPLACE), the snapshot's
        // main/dependencies value (which contains only "aws.greengrass.Nucleus" or is empty)
        // REPLACES the current topic value. This fires setupDependencies() with a list that
        // does not contain the builtin.
        //
        // Simulating the rollback snapshot restore: main/dependencies reverts to pre-deployment
        // state which only had Nucleus (before the IOTAutomation components were added).
        depTopic.withValue(Collections.singletonList("C"));
        context.runOnPublishQueueAndWait(() -> {});

        // Phase 4: Builtin is evicted. It remains running (service object is alive) but is
        // unreachable from main's dependency graph → invisible to orderedDependencies() → FSS
        // stops reporting it in CADENCE messages.
        assertNull(mainService.dependencies.get(builtinService),
                "BUG REPRODUCED: Rollback snapshot replay evicted the builtin from main's dependency "
                + "map because isDefaultDependency was already erased by the forward-merge of the same "
                + "deployment. The builtin will not appear in orderedDependencies() or FSS reports "
                + "for the remainder of this boot session. Only a restart re-injects it.");
    }

    @Test
    @DisplayName("Baseline: boot re-injection repairs the map (damage is session-scoped)")
    void GIVEN_builtin_evicted_WHEN_reinjected_with_default_flag_THEN_protection_restored() throws Exception {
        // Reproduce the eviction (same as above, condensed)
        mainService.addOrUpdateDependency(builtinService, DependencyType.HARD, true);
        Topic depTopic = mainService.getConfig().find(SERVICE_DEPENDENCIES_NAMESPACE_TOPIC);
        depTopic.withValue(Arrays.asList("B:HARD", "C"));
        context.runOnPublishQueueAndWait(() -> {});
        depTopic.withValue(Collections.singletonList("C"));
        context.runOnPublishQueueAndWait(() -> {});
        assertNull(mainService.dependencies.get(builtinService), "Precondition: builtin must be evicted");

        // Simulate what happens on next boot: KernelLifecycle re-injects with isDefault=true
        mainService.addOrUpdateDependency(builtinService, DependencyType.HARD, true);

        // Builtin is back and protected again
        assertNotNull(mainService.dependencies.get(builtinService),
                "Re-injection at boot must restore the builtin");
        assertTrue(mainService.dependencies.get(builtinService).isDefaultDependency,
                "Re-injection must restore the isDefaultDependency=true protection");

        // And it survives a subsequent omission (protection works again until next erasure)
        depTopic.withValue(Collections.singletonList("C"));
        context.runOnPublishQueueAndWait(() -> {});
        assertNotNull(mainService.dependencies.get(builtinService),
                "After re-injection, builtin must survive omission from dependency list");
    }
}
