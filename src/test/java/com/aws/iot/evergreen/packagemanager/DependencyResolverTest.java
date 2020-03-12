/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.exceptions.UnexpectedPackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.PackageStore;
import com.vdurmont.semver4j.Requirement;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class DependencyResolverTest {
    @Mock
    private PackageStore mockPackageStore;

    @BeforeAll
    public static void Setup() {
        System.setProperty("log.fmt", "TEXT");
        System.setProperty("log.store", "CONSOLE");
        System.setProperty("log.level", "DEBUG");
    }

    @Nested
    class MergeSemverRequirementsTest {
        @Spy
        private DependencyResolver resolver = new DependencyResolver(mockPackageStore);

        @Test
        public void GIVEN_list_of_version_ranges_WHEN_get_union_THEN_get_version_range() {
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
        public void GIVEN_list_of_version_range_and_pinned_version_WHEN_get_union_THEN_get_pinned_version() {
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
        public void GIVEN_list_of_version_range_with_conflicts_WHEN_get_union_THEN_get_no_version_match() {
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
    class GetVersionsToExploreTest{
        @Test
        public void GIVEN_package_not_active_WHEN_get_versions_THEN_get_version_within_constraint()
                throws UnexpectedPackagingException {
            when(mockPackageStore.getPackageVersionsIfExists("testPackage")).thenReturn(Arrays.asList(new Semver("1.2" +
                    ".0"), new Semver("1.1.0"), new Semver("1.0.0")));
            DependencyResolver resolver = spy(new DependencyResolver(mockPackageStore));
            doReturn(Optional.empty()).when(resolver).getPackageVersionIfActive(any());

            List<Semver> versions = resolver.getVersionsToExplore("testPackage", Arrays.asList(">1.0"));
            assertEquals(Arrays.asList(new Semver("1.2.0"), new Semver("1.1.0")), versions);
        }

        @Test
        public void GIVEN_package_active_WHEN_get_versions_THEN_get_active_version_first()
                throws UnexpectedPackagingException {
            when(mockPackageStore.getPackageVersionsIfExists("testPackage")).thenReturn(Arrays.asList(new Semver("1" +
                    ".2.0"), new Semver("1.1.0"), new Semver("1.0.0")));
            DependencyResolver resolver = spy(new DependencyResolver(mockPackageStore));
            doReturn(Optional.of("1.1.0")).when(resolver).getPackageVersionIfActive(any());

            List<Semver> versions = resolver.getVersionsToExplore("testPackage", Arrays.asList(">1.0"));
            assertEquals(Arrays.asList(new Semver("1.1.0"), new Semver("1.2.0")), versions);
        }
    }

    @Nested
    class ResolveDependenciesTest{
        private final Semver v1_1_0 = new Semver("1.1.0");
        private final Semver v1_0_0 = new Semver("1.0.0");
        private final String pkgA = "A";
        private final String pkgB1 = "B1";
        private final String pkgB2 = "B2";
        private final String pkgC1 = "C1";

        /**
         *    A
         *  /   \
         *  B1  B2
         *  \
         *  C1
         */
        @Test
        public void GIVEN_package_A_WHEN_resolve_dependencies_THEN_resolve_A_and_dependency_versions()
                throws PackageVersionConflictException, IOException, PackagingException {
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put("B1", "1.0");
            dependenciesA_1_x.put("B2", ">1.0");

            Map<String, String> dependenciesB1_1_x = new HashMap<>();
            dependenciesB1_1_x.put("C1", "1.0.0");

            when(mockPackageStore.getPackageVersionsIfExists(pkgA)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgB1)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgB2)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgC1)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));

            when(mockPackageStore.getPackage(pkgA, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgA, v1_0_0, "", ""
                    , Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesA_1_x, Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgB1, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgB1, v1_0_0, "",
                    "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesB1_1_x,
                    Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgB2, v1_1_0)).thenReturn(Optional.of(new Package(null, pkgB2, v1_1_0,
                    "", "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgC1, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgC1, v1_0_0, "",
                    "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList())));

            DependencyResolver resolver = spy(new DependencyResolver(mockPackageStore));
            doReturn(Optional.empty()).when(resolver).getPackageVersionIfActive(any());

            DeploymentDocument doc = new DeploymentDocument("mockJob1", Arrays.asList(pkgA),
                    Arrays.asList(new DeploymentPackageConfiguration(pkgA, v1_0_0.toString(), "", new HashSet<>(),
                            new ArrayList<>())), "mockGroup1", 1L);
            List<PackageIdentifier> result = resolver.resolveDependencies(doc);

            assertEquals(4, result.size());
            assertThat(result, containsInAnyOrder(new PackageIdentifier(pkgA, v1_0_0),
                    new PackageIdentifier(pkgB1, v1_0_0), new PackageIdentifier(pkgB2, v1_1_0),
                    new PackageIdentifier(pkgC1, v1_0_0)));
        }

        /**
         *    A
         *  /
         *  B1   B2
         *  \   /
         *   C1
         */
        @Test
        public void GIVEN_package_A_B2_WHEN_dependencies_overlap_THEN_satisfy_both()
                throws PackageVersionConflictException, IOException, PackagingException {
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put("B1", "1.0");

            Map<String, String> dependenciesB1_1_x = new HashMap<>();
            dependenciesB1_1_x.put("C1", "<2.0");

            Map<String, String> dependenciesB2_1_x = new HashMap<>();
            dependenciesB2_1_x.put("C1", ">1.0");

            when(mockPackageStore.getPackageVersionsIfExists(pkgA)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgB1)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgB2)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgC1)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));

            when(mockPackageStore.getPackage(pkgA, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgA, v1_0_0, "", ""
                    , Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesA_1_x, Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgB1, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgB1, v1_0_0, "",
                    "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesB1_1_x,
                    Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgB2, v1_1_0)).thenReturn(Optional.of(new Package(null, pkgB2, v1_1_0,
                    "", "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesB2_1_x,
                    Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgC1, v1_1_0)).thenReturn(Optional.of(new Package(null, pkgC1, v1_1_0, "",
                    "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), Collections.emptyMap(),
                    Collections.emptyList())));

            DependencyResolver resolver = spy(new DependencyResolver(mockPackageStore));
            doReturn(Optional.empty()).when(resolver).getPackageVersionIfActive(any());

            // top-level package order: A, B2
            DeploymentDocument doc = new DeploymentDocument("mockJob1", Arrays.asList(pkgA, pkgB2), Arrays.asList(
                    new DeploymentPackageConfiguration(pkgA, v1_0_0.toString(), "", new HashSet<>(), new ArrayList<>()),
                    new DeploymentPackageConfiguration(pkgB2, v1_1_0.toString(), "", new HashSet<>(), new ArrayList<>())
            ), "mockGroup1", 1L);
            List<PackageIdentifier> result = resolver.resolveDependencies(doc);

            assertEquals(4, result.size());
            assertThat(result, containsInAnyOrder(new PackageIdentifier(pkgA, v1_0_0),
                    new PackageIdentifier(pkgB1, v1_0_0), new PackageIdentifier(pkgB2, v1_1_0),
                    new PackageIdentifier(pkgC1, v1_1_0)));

            // top-level package order: B2, A
            doc = new DeploymentDocument("mockJob2", Arrays.asList(pkgB2, pkgA), Arrays.asList(
                    new DeploymentPackageConfiguration(pkgA, v1_0_0.toString(), "", new HashSet<>(), new ArrayList<>()),
                    new DeploymentPackageConfiguration(pkgB2, v1_1_0.toString(), "", new HashSet<>(), new ArrayList<>())
            ), "mockGroup1", 1L);
            result = resolver.resolveDependencies(doc);

            assertEquals(4, result.size());
            assertThat(result, containsInAnyOrder(new PackageIdentifier(pkgA, v1_0_0),
                    new PackageIdentifier(pkgB1, v1_0_0), new PackageIdentifier(pkgB2, v1_1_0),
                    new PackageIdentifier(pkgC1, v1_1_0)));
        }

        /**
         *    A
         *  /
         *  B1   B2
         *  \   /
         *   C1
         */
        @Test
        public void GIVEN_package_A_B2_WHEN_dependencies_conflict_THEN_throws_conflict_error()
                throws IOException, PackagingException {
            Map<String, String> dependenciesA_1_x = new HashMap<>();
            dependenciesA_1_x.put("B1", "1.0");

            Map<String, String> dependenciesB1_1_x = new HashMap<>();
            dependenciesB1_1_x.put("C1", "<1.0");

            Map<String, String> dependenciesB2_1_x = new HashMap<>();
            dependenciesB2_1_x.put("C1", ">1.1");

            when(mockPackageStore.getPackageVersionsIfExists(pkgA)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgB1)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgB2)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));
            when(mockPackageStore.getPackageVersionsIfExists(pkgC1)).thenReturn(Arrays.asList(v1_1_0, v1_0_0));

            when(mockPackageStore.getPackage(pkgA, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgA, v1_0_0, "", ""
                    , Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesA_1_x, Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgB1, v1_0_0)).thenReturn(Optional.of(new Package(null, pkgB1, v1_0_0, "",
                    "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesB1_1_x,
                    Collections.emptyList())));
            when(mockPackageStore.getPackage(pkgB2, v1_1_0)).thenReturn(Optional.of(new Package(null, pkgB2, v1_1_0,
                    "", "", Collections.emptySet(), Collections.emptyMap(), Collections.emptyList(), dependenciesB2_1_x,
                    Collections.emptyList())));

            DependencyResolver resolver = spy(new DependencyResolver(mockPackageStore));
            doReturn(Optional.empty()).when(resolver).getPackageVersionIfActive(any());

            // top-level package order: A, B2
            DeploymentDocument doc = new DeploymentDocument("mockJob1", Arrays.asList(pkgA, pkgB2), Arrays.asList(
                    new DeploymentPackageConfiguration(pkgA, v1_0_0.toString(), "", new HashSet<>(), new ArrayList<>()),
                    new DeploymentPackageConfiguration(pkgB2, v1_1_0.toString(), "", new HashSet<>(), new ArrayList<>())
            ), "mockGroup1", 1L);

            Exception thrown = assertThrows(PackageVersionConflictException.class,
                    () -> resolver.resolveDependencies(doc));
            assertEquals("Unresolved packages: [B2, A]", thrown.getMessage());

            // top-level package order: B2, A
            DeploymentDocument doc2 = new DeploymentDocument("mockJob2", Arrays.asList(pkgB2, pkgA), Arrays.asList(
                    new DeploymentPackageConfiguration(pkgA, v1_0_0.toString(), "", new HashSet<>(), new ArrayList<>()),
                    new DeploymentPackageConfiguration(pkgB2, v1_1_0.toString(), "", new HashSet<>(), new ArrayList<>())
            ), "mockGroup1", 1L);

            thrown = assertThrows(PackageVersionConflictException.class,
                    () -> resolver.resolveDependencies(doc2));
            assertEquals("Unresolved packages: [A, B2]", thrown.getMessage());
        }
    }
}
