/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.componentmanager.exceptions.NoAvailableComponentVersionException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.componentmanager.DependencyResolver.NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT;
import static com.aws.greengrass.componentmanager.DependencyResolver.NO_ACTIVE_NUCLEUS_VERSION_ERROR_MSG;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.NOTIFY_COMPONENTS;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DependencyResolverTest {

    private static final Semver v2_0_0 = new Semver("2.0.0");
    private static final Semver v1_5_0 = new Semver("1.5.0");
    private static final Semver v1_2_0 = new Semver("1.2.0");
    private static final Semver v1_1_0 = new Semver("1.1.0");
    private static final Semver v1_0_0 = new Semver("1.0.0");
    private static final String componentA = "A";
    private static final String componentB1 = "B1";
    private static final String componentB2 = "B2";
    private static final String componentC1 = "C1";
    private static final String componentX = "X";

    @InjectMocks
    private DependencyResolver dependencyResolver;

    @Mock
    private ComponentManager componentManager;

    @Mock
    private ComponentStore componentStore;

    @Mock
    private Kernel kernel;

    private Topics groupToTargetComponentsTopics;
    private Context context;
    private final ComponentUpdatePolicy componentUpdatePolicy =
            new ComponentUpdatePolicy(60, NOTIFY_COMPONENTS);
    private final DeploymentConfigurationValidationPolicy configurationValidationPolicy =
            DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(20).build();

    @BeforeEach
    void setupTopics() throws Exception {
        context = new Context();
        groupToTargetComponentsTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
        ComponentRecipe componentRecipe = mock(ComponentRecipe.class);
        lenient().when(componentRecipe.getComponentType()).thenReturn(ComponentType.GENERIC);
        lenient().when(componentStore.getPackageRecipe(any())).thenReturn(componentRecipe);
    }

    @AfterEach
    void cleanTopics() throws IOException {
        context.close();
    }

    @Test
    void GIVEN_circular_dependency_for_root_component_WHEN_resolve_dependency_called_THEN_resolution_fails() throws Exception {
        /*
         *      group1
         *         \(1.0.0)
         *     |--> A
         *  1.0.0  / \
         *     |__/   \(>1.0)
         *             B2
         */

        // prepare A
        Map<String, String> dependenciesA_1_x = new HashMap<>();
        dependenciesA_1_x.put(componentA, "1.0.0");
        dependenciesA_1_x.put(componentB2, ">1.0");
        ComponentMetadata componentA_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentA, v1_0_0), dependenciesA_1_x);
        when(componentManager.resolveComponentVersion(eq(componentA), any(), anyString())).thenReturn(componentA_1_0_0);

        DeploymentDocument doc = new DeploymentDocument("mockJob1", Collections
                .singletonList(
                        new DeploymentPackageConfiguration(componentA, true, v1_0_0.getValue())),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, configurationValidationPolicy);

        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentA)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        context.runOnPublishQueueAndWait(() -> System.out.println("Waiting for queue to finish updating the config"));

        Exception e = assertThrows(PackagingException.class,
                () -> dependencyResolver.resolveDependencies(doc, groupToTargetComponentsTopics));
        assertEquals("Circular dependency detected for Component A", e.getMessage());
    }



    @Test
    void GIVEN_circular_dependency_for_non_root_component_WHEN_resolve_dependency_called_THEN_resolution_fails()
            throws Exception {
        /*
         *      group1
         *         \(1.0.0)
         *          A
         *           \
         *            \(=1.0.0)
         *         |-> B1
         *         |    \
         *         |     \(=1.0.0)
         *      (=1.0.0)  B2
         *         |       \(<=1.1.0)
         *         |        \
         *         |--------C1
         */

        // prepare A
        Map<String, String> dependenciesA_1_x = new HashMap<>();
        dependenciesA_1_x.put(componentB1, "1.0.0");
        ComponentMetadata componentA_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentA, v1_0_0), dependenciesA_1_x);
        when(componentManager.resolveComponentVersion(eq(componentA), any(), anyString())).thenReturn(componentA_1_0_0);

        // prepare B1
        Map<String, String> dependenciesB1_1_x = new HashMap<>();
        dependenciesB1_1_x.put(componentB2, "1.0.0");
        ComponentMetadata componentB1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB1, v1_0_0), dependenciesB1_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB1), any(), anyString()))
                .thenReturn(componentB1_1_0_0);


        // prepare B2
        Map<String, String> dependenciesB2_1_x = new HashMap<>();
        dependenciesB2_1_x.put(componentC1, "<=1.1.0");

        ComponentMetadata componentB2_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB2, v1_1_0), dependenciesB2_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB2), any(), anyString()))
                .thenReturn(componentB2_1_1_0);

        // prepare C1
        Map<String, String> dependenciesC1_0_0 = new HashMap<>();
        dependenciesC1_0_0.put(componentB1, "1.0.0");
        ComponentMetadata componentC1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_0_0), dependenciesC1_0_0);
        when(componentManager.resolveComponentVersion(eq(componentC1), any(), anyString()))
                .thenReturn(componentC1_1_0_0);

        DeploymentDocument doc = new DeploymentDocument("mockJob1", Collections
                .singletonList(
                        new DeploymentPackageConfiguration(componentA, true, v1_0_0.getValue())),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, configurationValidationPolicy);

        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentA)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        context.runOnPublishQueueAndWait(() -> System.out.println("Waiting for queue to finish updating the config"));

        Exception e = assertThrows(PackagingException.class,
                () -> dependencyResolver.resolveDependencies(doc, groupToTargetComponentsTopics));
        assertEquals("Circular dependency detected for Component B1", e.getMessage());
    }

    @Test
    void GIVEN_component_A_WHEN_resolve_dependencies_THEN_resolve_A_and_dependency_versions() throws Exception {
        /*
         *      group1
         *         \(1.0.0)
         *          A
         * (1.0.0)/   \(>1.0)
         *      B1     B2
         *       \(1.0.0)
         *        C1
         */

        // prepare A
        Map<String, String> dependenciesA_1_x = new HashMap<>();
        dependenciesA_1_x.put(componentB1, "1.0.0");
        dependenciesA_1_x.put(componentB2, ">1.0");
        ComponentMetadata componentA_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentA, v1_0_0), dependenciesA_1_x);
        when(componentManager.resolveComponentVersion(eq(componentA), any(), anyString())).thenReturn(componentA_1_0_0);

        // prepare B1
        Map<String, String> dependenciesB1_1_x = new HashMap<>();
        dependenciesB1_1_x.put(componentC1, "1.0.0");
        ComponentMetadata componentB1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB1, v1_0_0), dependenciesB1_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB1), any(), anyString()))
                .thenReturn(componentB1_1_0_0);

        // prepare B2
        ComponentMetadata componentB2_1_2_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB2, v1_2_0), Collections.emptyMap());
        when(componentManager.resolveComponentVersion(eq(componentB2), any(), anyString()))
                .thenReturn(componentB2_1_2_0);

        // prepare C1
        ComponentMetadata componentC1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_0_0), Collections.emptyMap());
        when(componentManager.resolveComponentVersion(eq(componentC1), any(), anyString()))
                .thenReturn(componentC1_1_0_0);

        DeploymentDocument doc = new DeploymentDocument("mockJob1", Collections
                .singletonList(
                        new DeploymentPackageConfiguration(componentA, true, v1_0_0.getValue())),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, configurationValidationPolicy);

        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentA)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        context.runOnPublishQueueAndWait(() -> System.out.println("Waiting for queue to finish updating the config"));

        List<ComponentIdentifier> result = dependencyResolver.resolveDependencies(doc, groupToTargetComponentsTopics);

        assertThat(result.size(), is(4));
        assertThat(result, containsInAnyOrder(new ComponentIdentifier(componentA, v1_0_0),
                new ComponentIdentifier(componentB1, v1_0_0), new ComponentIdentifier(componentB2, v1_2_0),
                new ComponentIdentifier(componentC1, v1_0_0)));
    }

    @Test
    void GIVEN_component_A_B2_WHEN_dependencies_overlap_THEN_satisfy_both() throws Exception {
        /*
         *             group1
         *    (1.0.0)/      \(1.1.0)
         *          A       B2
         *  (1.0.0)/       /
         *        B1      /
         * (>1.0.0)\     /(<=1.1.0)
         *           C1
         */

        // prepare A
        Map<String, String> dependenciesA_1_x = new HashMap<>();
        dependenciesA_1_x.put(componentB1, "1.0.0");

        ComponentMetadata componentA_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentA, v1_0_0), dependenciesA_1_x);
        when(componentManager.resolveComponentVersion(eq(componentA), any(), anyString())).thenReturn(componentA_1_0_0);

        // prepare B1
        Map<String, String> dependenciesB1_1_x = new HashMap<>();
        dependenciesB1_1_x.put(componentC1, ">1.0.0");

        ComponentMetadata componentB1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB1, v1_0_0), dependenciesB1_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB1), any(), anyString()))
                .thenReturn(componentB1_1_0_0);

        // prepare B2
        Map<String, String> dependenciesB2_1_x = new HashMap<>();
        dependenciesB2_1_x.put(componentC1, "<=1.1.0");

        ComponentMetadata componentB2_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB2, v1_1_0), dependenciesB2_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB2), any(), anyString()))
                .thenReturn(componentB2_1_1_0);

        // prepare C1
        ComponentMetadata componentC_1_5_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_5_0), Collections.emptyMap());
        when(componentManager.resolveComponentVersion(eq(componentC1),
                eq(Collections.singletonMap(componentB1, Requirement.buildNPM(">1.0.0"))), anyString()))
                .thenReturn(componentC_1_5_0);
        ComponentMetadata componentC_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_1_0), Collections.emptyMap());
        Map<String, Requirement> versionRequirementMap = new HashMap<>();
        versionRequirementMap.put(componentB1, Requirement.buildNPM(">1.0.0"));
        versionRequirementMap.put(componentB2, Requirement.buildNPM("<=1.1.0"));
        when(componentManager.resolveComponentVersion(eq(componentC1), eq(versionRequirementMap), anyString()))
                .thenReturn(componentC_1_1_0);

        // top-level package order: A, B2
        DeploymentDocument doc = new DeploymentDocument("mockJob1",
                Arrays.asList(new DeploymentPackageConfiguration(componentA, true, v1_0_0.getValue()),
                        new DeploymentPackageConfiguration(componentB2, true, v1_1_0.getValue())),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, configurationValidationPolicy);

        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentA)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentB2)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.1.0"));
        context.runOnPublishQueueAndWait(() -> System.out.println("Waiting for queue to finish updating the config"));
        List<ComponentIdentifier> result = dependencyResolver.resolveDependencies(doc, groupToTargetComponentsTopics);

        assertThat(result.size(), is(4));
        assertThat(result, containsInAnyOrder(new ComponentIdentifier(componentA, v1_0_0),
                new ComponentIdentifier(componentB1, v1_0_0), new ComponentIdentifier(componentB2, v1_1_0),
                new ComponentIdentifier(componentC1, v1_1_0)));
        ArgumentCaptor<String> componentNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Requirement>> versionRequirementsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(componentManager, times(5))
                .resolveComponentVersion(componentNameCaptor.capture(), versionRequirementsCaptor.capture(),
                        eq("mockJob1"));
        List<String> componentNameList = componentNameCaptor.getAllValues();
        assertThat(componentNameList, contains("A", "B1", "C1", "B2", "C1"));
        List<Map<String, Requirement>> versionRequirementsList = versionRequirementsCaptor.getAllValues();
        assertThat(versionRequirementsList.size(), is(5));
        Map<String, Requirement> versionRequirements = versionRequirementsList.get(2);
        assertThat(versionRequirements.size(), is(1));
        assertThat(versionRequirements, IsMapContaining.hasEntry("B1", Requirement.buildNPM(">1.0.0")));
        versionRequirements = versionRequirementsList.get(4);
        assertThat(versionRequirements.size(), is(2));
        assertThat(versionRequirements, IsMapContaining.hasEntry("B1", Requirement.buildNPM(">1.0.0")));
        assertThat(versionRequirements, IsMapContaining.hasEntry("B2", Requirement.buildNPM("<=1.1.0")));
    }

    @Test
    void GIVEN_component_A_B2_WHEN_dependencies_conflict_THEN_throws_no_available_version_error() throws Exception {
        /*
         *             group1
         *    (1.0.0)/      \(1.1.0)
         *          A       B2
         *  (1.0.0)/       /
         *        B1      /
         * (<=1.1.0)\    /(>=1.2.0)
         *           C1
         */

        // prepare A
        Map<String, String> dependenciesA_1_x = new HashMap<>();
        dependenciesA_1_x.put(componentB1, "1.0.0");

        ComponentMetadata componentA_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentA, v1_0_0), dependenciesA_1_x);
        when(componentManager.resolveComponentVersion(eq(componentA), any(), anyString())).thenReturn(componentA_1_0_0);

        // prepare B1
        Map<String, String> dependenciesB1_1_x = new HashMap<>();
        dependenciesB1_1_x.put(componentC1, "<=1.1.0");

        ComponentMetadata componentB1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB1, v1_0_0), dependenciesB1_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB1), any(), anyString()))
                .thenReturn(componentB1_1_0_0);

        // prepare B2
        Map<String, String> dependenciesB2_1_x = new HashMap<>();
        dependenciesB2_1_x.put(componentC1, ">=1.2.0");

        ComponentMetadata componentB2_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB2, v1_1_0), dependenciesB2_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB2), any(), anyString()))
                .thenReturn(componentB2_1_1_0);

        // prepare C1
        ComponentMetadata componentC_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_1_0), Collections.emptyMap());
        when(componentManager.resolveComponentVersion(eq(componentC1),
                eq(Collections.singletonMap(componentB1, Requirement.buildNPM("<=1.1.0"))), anyString()))
                .thenReturn(componentC_1_1_0);
        Map<String, Requirement> versionRequirements = new HashMap<>();
        versionRequirements.put(componentB1, Requirement.buildNPM("<=1.1.0"));
        versionRequirements.put(componentB2, Requirement.buildNPM(">=1.2.0"));
        when(componentManager.resolveComponentVersion(eq(componentC1), eq(versionRequirements), anyString()))
                .thenThrow(NoAvailableComponentVersionException.class);

        // top-level package order: A, B2
        DeploymentDocument doc = new DeploymentDocument("mockJob1",
                Arrays.asList(new DeploymentPackageConfiguration(componentA, true, v1_0_0.getValue()),
                        new DeploymentPackageConfiguration(componentB2, true, v1_1_0.getValue())),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, configurationValidationPolicy);

        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentA)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentB2)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.1.0"));
        context.runOnPublishQueueAndWait(() -> System.out.println("Waiting for queue to finish updating the config"));
        assertThrows(NoAvailableComponentVersionException.class,
                () -> dependencyResolver.resolveDependencies(doc, groupToTargetComponentsTopics));
    }

    @Test
    void GIVEN_other_group_have_same_dependency_WHEN_deploy_current_group_THEN_resolve_dependency_version()
            throws Exception {
        /*
         *             group1            group2
         *    (1.0.0)/      \(1.1.0)      / (2.0.0)
         *          A       B2           X
         *  (1.0.0)/        /           /
         *        B1       /(<=1.2.0) / (>=1.0.0)
         * (>=1.1.0)\     /         /
         *                C1
         */

        // prepare A
        Map<String, String> dependenciesA_1_x = new HashMap<>();
        dependenciesA_1_x.put(componentB1, "1.0.0");

        ComponentMetadata componentA_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentA, v1_0_0), dependenciesA_1_x);
        when(componentManager.resolveComponentVersion(eq(componentA), any(), anyString())).thenReturn(componentA_1_0_0);

        // prepare B1
        Map<String, String> dependenciesB1_1_x = new HashMap<>();
        dependenciesB1_1_x.put(componentC1, ">=1.1.0");

        ComponentMetadata componentB1_1_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB1, v1_0_0), dependenciesB1_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB1), any(), anyString()))
                .thenReturn(componentB1_1_0_0);

        // prepare B2
        Map<String, String> dependenciesB2_1_x = new HashMap<>();
        dependenciesB2_1_x.put(componentC1, "<=1.2.0");

        ComponentMetadata componentB2_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentB2, v1_1_0), dependenciesB2_1_x);
        when(componentManager.resolveComponentVersion(eq(componentB2), any(), anyString()))
                .thenReturn(componentB2_1_1_0);

        // prepare C1
        ComponentMetadata componentC_1_2_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_2_0), Collections.emptyMap());
        when(componentManager.resolveComponentVersion(eq(componentC1), any(), anyString()))
                .thenReturn(componentC_1_2_0);
        ComponentMetadata componentC_1_1_0 =
                new ComponentMetadata(new ComponentIdentifier(componentC1, v1_1_0), Collections.emptyMap());
        when(componentManager.getActiveAndSatisfiedComponentMetadata(eq(componentC1), any()))
                .thenReturn(componentC_1_1_0);

        // prepare X
        Map<String, String> dependenciesX_2_x = new HashMap<>();
        dependenciesX_2_x.put(componentC1, ">=1.0.0");

        ComponentMetadata componentX_2_0_0 =
                new ComponentMetadata(new ComponentIdentifier(componentX, v2_0_0), dependenciesX_2_x);
        when(componentManager.getActiveAndSatisfiedComponentMetadata(eq(componentX), any()))
                .thenReturn(componentX_2_0_0);


        // top-level package order: A, B2
        DeploymentDocument doc = new DeploymentDocument("mockJob1",
                Arrays.asList(new DeploymentPackageConfiguration(componentA, true, v1_0_0.getValue()),
                        new DeploymentPackageConfiguration(componentB2, true, v1_1_0.getValue())),
                "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, configurationValidationPolicy);

        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentA)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
        groupToTargetComponentsTopics.lookupTopics("mockGroup1").lookupTopics(componentB2)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.1.0"));
        groupToTargetComponentsTopics.lookupTopics("mockGroup2").lookupTopics(componentX)
                .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "2.0.0"));
        context.runOnPublishQueueAndWait(() -> System.out.println("Waiting for queue to finish updating the config"));

        List<ComponentIdentifier> result = dependencyResolver.resolveDependencies(doc, groupToTargetComponentsTopics);

        assertThat(result.size(), is(5));
        assertThat(result, containsInAnyOrder(new ComponentIdentifier(componentA, v1_0_0),
                new ComponentIdentifier(componentB1, v1_0_0), new ComponentIdentifier(componentB2, v1_1_0),
                new ComponentIdentifier(componentC1, v1_2_0), new ComponentIdentifier(componentX, v2_0_0)));
        ArgumentCaptor<String> componentNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Requirement>> versionRequirementsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(componentManager, times(5))
                .resolveComponentVersion(componentNameCaptor.capture(), versionRequirementsCaptor.capture(),
                        eq("mockJob1"));
        List<String> componentNameList = componentNameCaptor.getAllValues();
        assertThat(componentNameList, contains("A", "B1", "C1", "B2", "C1"));
        List<Map<String, Requirement>> versionRequirementsList = versionRequirementsCaptor.getAllValues();
        assertThat(versionRequirementsList.size(), is(5));
        Map<String, Requirement> versionRequirements = versionRequirementsList.get(2);
        assertThat(versionRequirements.size(), is(2));
        assertThat(versionRequirements, IsMapContaining.hasEntry("B1", Requirement.buildNPM(">=1.1.0")));
        assertThat(versionRequirements, IsMapContaining.hasEntry("X", Requirement.buildNPM(">=1.0.0")));
        versionRequirements = versionRequirementsList.get(4);
        assertThat(versionRequirements.size(), is(3));
        assertThat(versionRequirements, IsMapContaining.hasEntry("X", Requirement.buildNPM(">=1.0.0")));
        assertThat(versionRequirements, IsMapContaining.hasEntry("B1", Requirement.buildNPM(">=1.1.0")));
        assertThat(versionRequirements, IsMapContaining.hasEntry("B2", Requirement.buildNPM("<=1.2.0")));
    }

    @Test
    void GIVEN_resolved_components_contains_new_nucleus_patch_version_WHEN_minor_version_is_unchanged_THEN_pass()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        // Only path version is proposed to change
        ComponentIdentifier defaultNucleusNewVersion = new ComponentIdentifier("defaultNucleus", new Semver("1.0.3"));
        ComponentRecipe defaultNucleusNewVersionRecipe = mock(ComponentRecipe.class);
        when(defaultNucleusNewVersionRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(defaultNucleusNewVersion)).thenReturn(defaultNucleusNewVersionRecipe);

        dependencyResolver.checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                Arrays.asList(componentA, componentB, defaultNucleusNewVersion));
    }

    @Test
    void GIVEN_resolved_components_contains_new_nucleus_minor_version_WHEN_active_nucleus_minor_version_is_different_THEN_fail()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier defaultNucleusNewVersion = new ComponentIdentifier("defaultNucleus", new Semver("1.2.0"));
        ComponentRecipe defaultNucleusNewVersionRecipe = mock(ComponentRecipe.class);
        when(defaultNucleusNewVersionRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(defaultNucleusNewVersion)).thenReturn(defaultNucleusNewVersionRecipe);

        Exception e = assertThrows(PackagingException.class, () -> dependencyResolver
                .checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                        Arrays.asList(componentA, componentB, defaultNucleusNewVersion)));
        assertEquals(String.format(NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT, "defaultNucleus", "1.0.0",
                "defaultNucleus", "1.2.0"), e.getMessage());
    }

    @Test
    void GIVEN_resolved_components_contains_new_nucleus_major_version_WHEN_active_nucleus_major_version_is_different_THEN_fail()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier defaultNucleusNewVersion = new ComponentIdentifier("defaultNucleus", new Semver("2.0.0"));
        ComponentRecipe defaultNucleusNewVersionRecipe = mock(ComponentRecipe.class);
        when(defaultNucleusNewVersionRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(defaultNucleusNewVersion)).thenReturn(defaultNucleusNewVersionRecipe);

        Exception e = assertThrows(PackagingException.class, () -> dependencyResolver
                .checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                        Arrays.asList(componentA, componentB, defaultNucleusNewVersion)));
        assertEquals(String.format(NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT, "defaultNucleus", "1.0.0",
                "defaultNucleus", "2.0.0"), e.getMessage());
    }

    @Test
    void GIVEN_resolved_components_contains_nucleus_WHEN_active_nucleus_version_is_unchanged_THEN_pass()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier defaultNucleusSameVersion = new ComponentIdentifier("defaultNucleus", new Semver("1.0.0"));
        ComponentRecipe defaultNucleusSameVersionsRecipe = mock(ComponentRecipe.class);
        when(defaultNucleusSameVersionsRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(defaultNucleusSameVersion)).thenReturn(defaultNucleusSameVersionsRecipe);

        dependencyResolver.checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                Arrays.asList(componentA, componentB, defaultNucleusSameVersion));
    }

    @Test
    void GIVEN_resolved_components_contains_no_nucleus_WHEN_active_nucleus_version_is_anything_THEN_pass()
            throws Exception {
        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));

        dependencyResolver
                .checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"), Arrays.asList(componentA, componentB));
    }

    @Test
    void GIVEN_resolved_components_contains_new_nucleus_minor_version_WHEN_new_nucleus_is_target_component_THEN_pass()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier newNucleus = new ComponentIdentifier("defaultNucleus", new Semver("1.2.0"));
        ComponentRecipe newNucleusRecipe = mock(ComponentRecipe.class);
        when(newNucleusRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(newNucleus)).thenReturn(newNucleusRecipe);

        dependencyResolver.checkNonExplicitNucleusUpdate(Arrays.asList("A", "B", "defaultNucleus"),
                Arrays.asList(componentA, componentB, newNucleus));
    }

    @Test
    void GIVEN_resolved_components_contains_new_nucleus_major_version_WHEN_new_nucleus_is_target_component_THEN_pass()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier newNucleus = new ComponentIdentifier("defaultNucleus", new Semver("2.0.0"));
        ComponentRecipe newNucleusRecipe = mock(ComponentRecipe.class);
        when(newNucleusRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(newNucleus)).thenReturn(newNucleusRecipe);

        dependencyResolver.checkNonExplicitNucleusUpdate(Arrays.asList("A", "B", "defaultNucleus"),
                Arrays.asList(componentA, componentB, newNucleus));
    }

    @Test
    void GIVEN_resolved_components_contains_custom_nucleus_WHEN_custom_nucleus_is_target_component_THEN_pass()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier customNucleus = new ComponentIdentifier("customNucleus", new Semver("3.4.5"));
        ComponentRecipe customNucleusRecipe = mock(ComponentRecipe.class);
        when(customNucleusRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(customNucleus)).thenReturn(customNucleusRecipe);

        dependencyResolver.checkNonExplicitNucleusUpdate(Arrays.asList("A", "B", "customNucleus"),
                Arrays.asList(componentA, componentB, customNucleus));
    }

    @Test
    void GIVEN_resolved_components_contains_custom_nucleus_WHEN_custom_nucleus_is_not_target_component_THEN_fail()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        when(activeNucleus.getServiceName()).thenReturn("defaultNucleus");
        Topic versionTopic = Topic.of(context, "version", "1.0.0");
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(versionTopic);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier customNucleus = new ComponentIdentifier("customNucleus", new Semver("1.2.0"));
        ComponentRecipe customNucleusRecipe = mock(ComponentRecipe.class);
        when(customNucleusRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(customNucleus)).thenReturn(customNucleusRecipe);

        Exception e = assertThrows(PackagingException.class, () -> dependencyResolver
                .checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                        Arrays.asList(componentA, componentB, customNucleus)));
        assertEquals(String.format(NON_EXPLICIT_NUCLEUS_UPDATE_ERROR_MESSAGE_FMT, "defaultNucleus", "1.0.0",
                "customNucleus", "1.2.0"), e.getMessage());
    }

    @Test
    void GIVEN_resolved_components_contains_multiple_nuclei_WHEN_active_nucleus_version_is_anything_THEN_fail()
            throws Exception {
        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier defaultNucleusNewVersion = new ComponentIdentifier("defaultNucleus", new Semver("1.2.0"));
        ComponentIdentifier defaultNucleusNewerVersion = new ComponentIdentifier("defaultNucleus", new Semver("2.0.0"));
        ComponentRecipe defaultNucleusNewVersionRecipe = mock(ComponentRecipe.class);
        when(defaultNucleusNewVersionRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(defaultNucleusNewVersion)).thenReturn(defaultNucleusNewVersionRecipe);
        ComponentRecipe defaultNucleusNewerVersionRecipe = mock(ComponentRecipe.class);
        when(defaultNucleusNewerVersionRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(defaultNucleusNewerVersion)).thenReturn(defaultNucleusNewerVersionRecipe);

        Exception e = assertThrows(PackagingException.class, () -> dependencyResolver
                .checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                        Arrays.asList(componentA, componentB, defaultNucleusNewVersion, defaultNucleusNewerVersion)));
        assertTrue(e.getMessage().contains("Deployment cannot have more than 1 component of type Nucleus"));
    }

    @Test
    void GIVEN_resolved_components_contains_nucleus_WHEN_active_nucleus_version_config_missing_THEN_fail()
            throws Exception {
        GreengrassService activeNucleus = mock(GreengrassService.class);
        Topics serviceConfig = mock(Topics.class);
        when(serviceConfig.find("version")).thenReturn(null);
        when(activeNucleus.getServiceConfig()).thenReturn(serviceConfig);
        when(activeNucleus.getServiceType()).thenReturn(ComponentType.NUCLEUS.name());
        List<GreengrassService> activeServices = Collections.singletonList(activeNucleus);
        when(kernel.orderedDependencies()).thenReturn(activeServices);

        ComponentIdentifier componentA = new ComponentIdentifier("A", new Semver("1.0.0"));
        ComponentIdentifier componentB = new ComponentIdentifier("B", new Semver("1.0.0"));
        ComponentIdentifier customNucleus = new ComponentIdentifier("customNucleus", new Semver("1.2.0"));
        ComponentRecipe customNucleusRecipe = mock(ComponentRecipe.class);
        when(customNucleusRecipe.getComponentType()).thenReturn(ComponentType.NUCLEUS);
        when(componentStore.getPackageRecipe(customNucleus)).thenReturn(customNucleusRecipe);

        Exception e = assertThrows(PackagingException.class, () -> dependencyResolver
                .checkNonExplicitNucleusUpdate(Arrays.asList("A", "B"),
                        Arrays.asList(componentA, componentB, customNucleus)));
        assertEquals(NO_ACTIVE_NUCLEUS_VERSION_ERROR_MSG, e.getMessage());
    }
}
