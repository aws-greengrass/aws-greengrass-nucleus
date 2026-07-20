/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.component.common.DependencyType;
import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.DependencyResolver;
import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
 * Regression tests for thing-group state preservation in
 * {@code DefaultDeploymentTask.getNonTargetGroupToRootPackagesMap()}.
 *
 * <h2>The defect</h2>
 * <p>The set of root components a deployment keeps is computed exclusively from local bookkeeping
 * ({@code GroupToRootComponents}). A running root component whose group record was absent — for any reason,
 * including loss of persisted state — was invisible to that computation and silently removed by a LOCAL
 * deployment's merge, even when the cloud membership API truthfully confirmed the device still belongs to the
 * group. Local deployments are explicit add/remove deltas and have no authority for such implicit removals.
 *
 * <h2>The contract these tests encode</h2>
 * <p>A LOCAL deployment may only remove a currently-running root component with positive evidence, exactly one
 * of: the request explicitly lists it for removal, or freshly fetched cloud membership shows the device no
 * longer belongs to any group the component is attributed to. Absence of local bookkeeping is never evidence,
 * and unknown membership is never treated as "member of no groups".
 *
 * <p>CLOUD deployment semantics are intentionally unchanged: a component is removed when the cloud deployment
 * no longer contains it, including for components whose bookkeeping was lost (a known limitation of the loss
 * state — redeploying each group rebuilds its records).
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
    private Topics runningComponentConfig;
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
        runningComponentConfig = Topics.of(context, RUNNING_COMPONENT_NAME, null);
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
     * REGRESSION TEST (fails without the fix) — the field-incident shape.
     *
     * <p>All group bookkeeping is lost. The component is still running as a root, and the cloud truthfully
     * confirms the device still belongs to its group. A local deployment (an explicit add/remove delta by
     * contract) must not remove the running component.
     */
    @Test
    void GIVEN_group_record_lost_and_component_running_WHEN_local_deployment_THEN_component_is_preserved()
            throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        DeploymentResult result = createTask(localDeployment(document)).call();

        assertEquals(DeploymentResult.DeploymentStatus.SUCCESSFUL, result.getDeploymentStatus());
        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertTrue(rootPackages != null && rootPackages.contains(RUNNING_COMPONENT_NAME),
                "REGRESSION: a running root component was dropped from a local deployment's root package set "
                        + "solely because its local GroupToRootComponents record is missing. Absence of "
                        + "bookkeeping is not evidence of removal; a local deployment may only remove a running "
                        + "root with positive evidence.");
        verify(mockThingGroupHelper).listThingGroupsForDevice(1);
    }

    /**
     * REGRESSION TEST (fails without the fix) — unknown membership must not mean "member of nothing".
     *
     * <p>The membership API reports "unknown" (device not configured to talk to the cloud). The healthy group
     * record must still preserve its component, exactly as the fetch-failure fallback paths already do.
     */
    @Test
    void GIVEN_membership_unknown_WHEN_local_deployment_THEN_recorded_group_components_are_preserved()
            throws Exception {
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
    void GIVEN_group_record_exists_and_membership_confirmed_WHEN_local_deployment_THEN_component_is_preserved()
            throws Exception {
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
     * device no longer belongs to the only group a running component is attributed to, a local deployment
     * removes the component. Positive evidence exists; preservation must not block it.
     */
    @Test
    void GIVEN_device_left_component_group_WHEN_local_deployment_THEN_component_is_removed() throws Exception {
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
     * LEGITIMATE-FLOW TEST (must pass with and without the fix): explicit removal in a local deployment request
     * is always honored, even when the bookkeeping that would normally reflect the component is lost.
     */
    @Test
    void GIVEN_records_lost_WHEN_local_deployment_explicitly_removes_component_THEN_component_is_removed()
            throws Exception {
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

    /**
     * FAIL-CLOSED TEST (must pass with and without the fix): a running root component whose configuration has
     * no version is not preserved. An open version requirement could resolve a different (e.g. cloud) component
     * of the same name, so preservation fails closed for versionless services.
     */
    @Test
    void GIVEN_running_component_has_no_version_WHEN_local_deployment_THEN_component_is_not_preserved()
            throws Exception {
        runningComponentConfig.remove(runningComponentConfig.find(VERSION_CONFIG_KEY));
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt()))
                .thenReturn(Optional.of(Collections.singleton(NON_TARGET_GROUP_NAME)));
        DeploymentDocument document = localDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(localDeployment(document)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertFalse(rootPackages.contains(RUNNING_COMPONENT_NAME),
                "Preservation must fail closed for a running component with no version to pin to");
    }

    /**
     * CLOUD-SEMANTICS TEST (must pass with and without the fix): cloud deployment behavior is intentionally
     * unchanged. A running component that no record or document accounts for is removed by a cloud deployment,
     * even when membership confirms the device still belongs to another group — a known limitation of the
     * bookkeeping-loss state. Recovery is a deployment for each of the device's groups, which rebuilds records.
     */
    @Test
    void GIVEN_records_lost_and_component_running_WHEN_cloud_deployment_THEN_component_is_removed()
            throws Exception {
        when(mockThingGroupHelper.listThingGroupsForDevice(anyInt())).thenReturn(Optional.of(
                new HashSet<>(Arrays.asList(CLOUD_TARGET_GROUP_NAME, NON_TARGET_GROUP_NAME))));
        DeploymentDocument document = cloudDeploymentDocument();
        mockSuccessfulPipeline(document);

        createTask(new Deployment(document, Deployment.DeploymentType.IOT_JOBS, "cloudJobId", DEFAULT)).call();

        List<String> rootPackages = rootPackagesCaptor.getValue();
        assertNotNull(rootPackages);
        assertTrue(rootPackages.contains(CLOUD_TARGET_ROOT_COMPONENT),
                "Target group's root component from the deployment document must be present");
        assertFalse(rootPackages.contains(RUNNING_COMPONENT_NAME),
                "Cloud deployment semantics are unchanged: a component not contained in the deployment or the "
                        + "preserved group records is removed");
    }
}
