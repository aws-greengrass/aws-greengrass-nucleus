/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.aws.greengrass.componentmanager.exceptions.ComponentVersionConflictException;
import com.aws.greengrass.componentmanager.exceptions.PackagingException;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentMetadata;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.utils.ImmutableMap;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_VERSION_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DependencyResolverTest {
    @InjectMocks
    private DependencyResolver resolver;

    @Mock
    private ComponentManager mockComponentManager;

    private Topics groupToRootPackagesTopics;
    private Context context;
    private final ComponentUpdatePolicy componentUpdatePolicy =
            new ComponentUpdatePolicy(60, ComponentUpdatePolicyAction.NOTIFY_COMPONENTS);

    @BeforeEach
    void setupTopics() {
        context = new Context();
        groupToRootPackagesTopics = Topics.of(context, GROUP_TO_ROOT_COMPONENTS_TOPICS, null);
    }

    @AfterEach
    void cleanupTopics() throws IOException {
        context.close();
    }

    @Nested
    class MergeSemverRequirementsTest {

        @Test
        void GIVEN_list_of_version_ranges_WHEN_get_union_THEN_get_version_range() {
            List<String> constraints = new LinkedList<>();
            constraints.add("<3.0");
            constraints.add(">1.0");
            constraints.add(">2.0");
            String req = resolver.mergeSemverRequirements(constraints);
            assertEquals("<3.0 >1.0 >2.0", req);

            Requirement r = Requirement.buildNPM(req);
            assertFalse(r.isSatisfiedBy("1.0.1"));
            assertTrue(r.isSatisfiedBy("2.0.1"));
        }

        @Test
        void GIVEN_list_of_version_range_and_pinned_version_WHEN_get_union_THEN_get_pinned_version() {
            List<String> constraints = new LinkedList<>();
            constraints.add("<3.0");
            constraints.add("1.0.0");
            String req = resolver.mergeSemverRequirements(constraints);
            assertEquals("<3.0 =1.0.0", req);

            Requirement r = Requirement.buildNPM(req);
            assertFalse(r.isSatisfiedBy("1.0.1"));
            assertTrue(r.isSatisfiedBy("1.0.0"));
        }

        @Test
        void GIVEN_list_of_version_range_with_conflicts_WHEN_get_union_THEN_get_no_version_match() {
            List<String> constraints = new LinkedList<>();
            constraints.add(">4.0");
            constraints.add("<3.0");
            String req = resolver.mergeSemverRequirements(constraints);
            assertEquals(">4.0 <3.0", req);

            Requirement r = Requirement.buildNPM(req);
            assertFalse(r.isSatisfiedBy("4.0.1"));
            assertFalse(r.isSatisfiedBy("2.0.0"));
        }
    }

    @Nested
    class ResolveDependenciesTest {
        private final Semver v1_2_0 = new Semver("1.2.0");
        private final Semver v1_1_0 = new Semver("1.1.0");
        private final Semver v1_0_0 = new Semver("1.0.0");
        private static final String pkgA = "A";
        private static final String pkgB1 = "B1";
        private static final String pkgB2 = "B2";
        private static final String pkgC1 = "C1";

        @Test
        void GIVEN_package_A_WHEN_resolve_dependencies_THEN_resolve_A_and_dependency_versions()
                throws ComponentVersionConflictException, IOException, PackagingException {

            /*
             *      main
             *         \(1.0.0)
             *          A
             * (1.0.0)/   \(>1.0)
             *      B1     B2
             *       \(1.0.0)
             *        C1
             */

            // prepare A
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put(pkgB1, "1.0.0");
            dependenciesA_1_x.put(pkgB2, ">1.0");

            ComponentMetadata packageA_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgA, v1_0_0), dependenciesA_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgA), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageA_1_0_0).iterator());

            // prepare B1
            Map<String, String> dependenciesB1_1_x = new HashMap<>();
            dependenciesB1_1_x.put(pkgC1, "1.0.0");

            ComponentMetadata packageB1_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB1, v1_0_0), dependenciesB1_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB1_1_0_0).iterator());

            // prepare B2
            // B2 has both 1.1.0 and 1.2.0 satisfying >1.0
            // expected to pick 1.1.0
            ComponentMetadata packageB2_1_1_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB2, v1_1_0), Collections.emptyMap());
            ComponentMetadata packageB2_1_2_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB2, v1_2_0), Collections.emptyMap());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB2), Mockito.any()))
                    .thenReturn(Arrays.asList(packageB2_1_1_0, packageB2_1_2_0).iterator());

            // prepare C1
            ComponentMetadata packageC_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgC1, v1_0_0), Collections.emptyMap());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgC1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageC_1_0_0).iterator());

            DeploymentDocument doc = new DeploymentDocument("mockJob1", Collections.singletonList(pkgA), Collections
                    .singletonList(new DeploymentPackageConfiguration(pkgA, true, v1_0_0.toString(), new HashMap<>())),
                    "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, null);

            groupToRootPackagesTopics.lookupTopics("mockGroup1").lookupTopics(pkgA)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
            context.runOnPublishQueueAndWait(
                    () -> System.out.println("Waiting for queue to finish updating the config"));
            List<ComponentIdentifier> result = resolver.resolveDependencies(doc, groupToRootPackagesTopics);

            assertEquals(4, result.size());
            assertThat(result,
                    containsInAnyOrder(new ComponentIdentifier(pkgA, v1_0_0), new ComponentIdentifier(pkgB1, v1_0_0),
                            new ComponentIdentifier(pkgB2, v1_1_0), new ComponentIdentifier(pkgC1, v1_0_0)));
        }

        @Test
        void GIVEN_package_A_B2_WHEN_dependencies_overlap_THEN_satisfy_both()
                throws ComponentVersionConflictException, IOException, PackagingException {

            /*
             *             main
             *    (1.0.0)/      \(1.0.0)
             *          A       B2
             *  (1.0.0)/       /
             *        B1      /
             * (<1.1.0)\     /(>=1.0.0)
             *           C1
             */

            // prepare A
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put(pkgB1, "1.0.0");

            ComponentMetadata packageA_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgA, v1_0_0), dependenciesA_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgA), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageA_1_0_0).iterator());

            // prepare B1
            Map<String, String> dependenciesB1_1_x = new HashMap<>();
            dependenciesB1_1_x.put(pkgC1, "<1.1.0");

            ComponentMetadata packageB1_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB1, v1_0_0), dependenciesB1_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB1_1_0_0).iterator());

            // prepare B2
            Map<String, String> dependenciesB2_1_x = new HashMap<>();
            dependenciesB2_1_x.put(pkgC1, ">=1.0.0");

            ComponentMetadata packageB2_1_1_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB2, v1_1_0), dependenciesB2_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB2), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB2_1_1_0).iterator());

            // prepare C1
            ComponentMetadata packageC_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgC1, v1_0_0), Collections.emptyMap());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgC1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageC_1_0_0).iterator());

            // top-level package order: A, B2
            DeploymentDocument doc = new DeploymentDocument("mockJob1", Arrays.asList(pkgA, pkgB2),
                    Arrays.asList(new DeploymentPackageConfiguration(pkgA, true, v1_0_0.toString(), new HashMap<>()),
                            new DeploymentPackageConfiguration(pkgB2, true, v1_1_0.toString(), new HashMap<>())),
                    "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, null);

            groupToRootPackagesTopics.lookupTopics("mockGroup1").lookupTopics(pkgA)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
            groupToRootPackagesTopics.lookupTopics("mockGroup1").lookupTopics(pkgB2)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.1.0"));
            context.runOnPublishQueueAndWait(
                    () -> System.out.println("Waiting for queue to finish updating the config"));
            List<ComponentIdentifier> result = resolver.resolveDependencies(doc, groupToRootPackagesTopics);

            assertEquals(4, result.size());
            assertThat(result,
                    containsInAnyOrder(new ComponentIdentifier(pkgA, v1_0_0), new ComponentIdentifier(pkgB1, v1_0_0),
                            new ComponentIdentifier(pkgB2, v1_1_0), new ComponentIdentifier(pkgC1, v1_0_0)));
            verify(mockComponentManager).listAvailablePackageMetadata(pkgC1, Requirement.buildNPM(">=1.0.0 <1.1.0"));

            // top-level package order: B2, A
            // refresh iterator
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgA), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageA_1_0_0).iterator());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB1_1_0_0).iterator());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB2), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB2_1_1_0).iterator());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgC1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageC_1_0_0).iterator());

            doc = new DeploymentDocument("mockJob2", Arrays.asList(pkgB2, pkgA),
                    Arrays.asList(new DeploymentPackageConfiguration(pkgB2, true, v1_1_0.toString(), new HashMap<>()),
                            new DeploymentPackageConfiguration(pkgA, true, v1_0_0.toString(), new HashMap<>())),
                    "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, null);

            result = resolver.resolveDependencies(doc, groupToRootPackagesTopics);
            verify(mockComponentManager).listAvailablePackageMetadata(pkgC1, Requirement.buildNPM(">=1.0.0 <1.1.0"));


            assertEquals(4, result.size());
            assertThat(result,
                    containsInAnyOrder(new ComponentIdentifier(pkgA, v1_0_0), new ComponentIdentifier(pkgB1, v1_0_0),
                            new ComponentIdentifier(pkgB2, v1_1_0), new ComponentIdentifier(pkgC1, v1_0_0)));
        }


        @Test
        void GIVEN_package_A_B2_WHEN_dependencies_conflict_THEN_throws_conflict_error()
                throws IOException, PackagingException {

            /*
             *             main
             *    (1.0.0)/      \(1.0.0)
             *          A       B2
             *  (1.0.0)/       /
             *        B1      /
             * (<1.0.0)\     /(>1.1.0)
             *           C1
             */


            // prepare A
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put(pkgB1, "1.0.0");

            ComponentMetadata packageA_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgA, v1_0_0), dependenciesA_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgA), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageA_1_0_0).iterator());

            // prepare B1
            Map<String, String> dependenciesB1_1_x = new HashMap<>();
            dependenciesB1_1_x.put(pkgC1, "<1.0.0");

            ComponentMetadata packageB1_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB1, v1_0_0), dependenciesB1_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB1_1_0_0).iterator());

            // prepare B2
            Map<String, String> dependenciesB2_1_x = new HashMap<>();
            dependenciesB2_1_x.put(pkgC1, ">1.1.0");

            ComponentMetadata packageB2_1_1_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB2, v1_1_0), dependenciesB2_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB2), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB2_1_1_0).iterator());

            // prepare C1
            lenient().when(mockComponentManager
                    .listAvailablePackageMetadata(eq(pkgC1), eq(Requirement.buildNPM(">1.1.0 <1.0.0"))))
                    .thenReturn(Collections.emptyIterator());

            // top-level package order: A, B2
            DeploymentDocument doc = new DeploymentDocument("mockJob1", Arrays.asList(pkgA, pkgB2),
                    Arrays.asList(new DeploymentPackageConfiguration(pkgA, true, v1_0_0.toString(), new HashMap<>()),
                            new DeploymentPackageConfiguration(pkgB2, true, v1_1_0.toString(), new HashMap<>())),
                    "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, null);

            groupToRootPackagesTopics.lookupTopics("mockGroup1", pkgA)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
            groupToRootPackagesTopics.lookupTopics("mockGroup1", pkgB2)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.1.0"));
            context.runOnPublishQueueAndWait(
                    () -> System.out.println("Waiting for queue to finish updating the config"));
            Exception thrown = assertThrows(ComponentVersionConflictException.class,
                    () -> resolver.resolveDependencies(doc, groupToRootPackagesTopics));
            assertThat(thrown.getMessage(), containsString("C1"));
            assertThat(thrown.getMessage(), containsString("{B2-v1.1.0=>1.1.0, B1-v1.0.0=<1.0.0}"));


            // top-level package order: B2, A
            // refresh iterator for A B1 and B2
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgA), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageA_1_0_0).iterator());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB1_1_0_0).iterator());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB2), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB2_1_1_0).iterator());

            // prepare C1
            ComponentMetadata packageC_1_2_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgC1, v1_2_0), Collections.emptyMap());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgC1), eq(Requirement.buildNPM(">1.1.0"))))
                    .thenReturn(Collections.singletonList(packageC_1_2_0).iterator());

            DeploymentDocument doc2 = new DeploymentDocument("mockJob2", Arrays.asList(pkgB2, pkgA),
                    Arrays.asList(new DeploymentPackageConfiguration(pkgB2, true, v1_1_0.toString(), new HashMap<>()),
                            new DeploymentPackageConfiguration(pkgA, true, v1_0_0.toString(), new HashMap<>())),
                    "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, null);
            thrown = assertThrows(ComponentVersionConflictException.class,
                    () -> resolver.resolveDependencies(doc2, groupToRootPackagesTopics));
            assertEquals("Package version C1-v1.2.0 does not satisfy requirements of B1-v1.0.0, which is: <1.0.0",
                    thrown.getMessage());
        }


        @Test
        void GIVEN_active_packages_WHEN_merge_in_packages_THEN_add_or_update_or_keep_or_delete_accordingly()
                throws IOException, PackagingException, ComponentVersionConflictException {

            /*
             * (add) A    (update) B1   (keep) B2   (delete) D
             *         \        |        |         /
             *                  (update) C1
             */

            // prepare A
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put(pkgC1, ">=1.0.0");
            ComponentMetadata packageA_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgA, v1_0_0), dependenciesA_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgA), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageA_1_0_0).iterator());

            // prepare B1
            Map<String, String> dependenciesB1_1_0 = new HashMap<>();
            dependenciesB1_1_0.put(pkgC1, ">=1.0.0");

            ComponentMetadata packageB1_1_0_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB1, v1_1_0), dependenciesB1_1_0);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB1_1_0_0).iterator());

            // prepare B2
            Map<String, String> dependenciesB2_1_x = new HashMap<>();
            dependenciesB2_1_x.put(pkgC1, ">=1.0.0");

            ComponentMetadata packageB2_1_1_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgB2, v1_0_0), dependenciesB2_1_x);
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgB2), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageB2_1_1_0).iterator());

            // prepare C1
            ComponentMetadata packageC_1_1_0 =
                    new ComponentMetadata(new ComponentIdentifier(pkgC1, v1_1_0), Collections.emptyMap());
            when(mockComponentManager.listAvailablePackageMetadata(eq(pkgC1), Mockito.any()))
                    .thenReturn(Collections.singletonList(packageC_1_1_0).iterator());

            // New deployment: A, B1
            DeploymentDocument doc = new DeploymentDocument("mockJob1", Arrays.asList(pkgA, pkgB1),
                    Arrays.asList(new DeploymentPackageConfiguration(pkgA, true, v1_0_0.toString(), new HashMap<>()),
                            new DeploymentPackageConfiguration(pkgB1, true, v1_1_0.toString(), new HashMap<>())),
                    "mockGroup1", 1L, FailureHandlingPolicy.DO_NOTHING, componentUpdatePolicy, null);

            groupToRootPackagesTopics.lookupTopics("mockGroup1", pkgA)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
            groupToRootPackagesTopics.lookupTopics("mockGroup1", pkgB1)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.1.0"));
            groupToRootPackagesTopics.lookupTopics("mockGroup2" ,pkgB2)
                    .replaceAndWait(ImmutableMap.of(GROUP_TO_ROOT_COMPONENTS_VERSION_KEY, "1.0.0"));
            context.runOnPublishQueueAndWait(
                    () -> System.out.println("Waiting for queue to finish updating the config"));

            // DA gives A, B1, B2 as root packages, meaning B2 is a root package for another group
            List<ComponentIdentifier> result = resolver.resolveDependencies(doc, groupToRootPackagesTopics);

            assertEquals(4, result.size());
            assertThat(result,
                    containsInAnyOrder(new ComponentIdentifier(pkgA, v1_0_0), new ComponentIdentifier(pkgB1, v1_1_0),
                            new ComponentIdentifier(pkgB2, v1_0_0), new ComponentIdentifier(pkgC1, v1_1_0)));
        }
    }
}
