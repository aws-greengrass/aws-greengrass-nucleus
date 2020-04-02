/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageParameter;
import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;
import com.aws.iot.evergreen.packagemanager.plugins.PackageStoreDeprecated;
import com.vdurmont.semver4j.Semver;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PackageManagerTest {

    private PackageManager packageManager;

    @Mock
    private PackageRegistry packageRegistry;

    @Mock
    private PackageStoreDeprecated packageStoreDeprecated;

    @Mock
    private PackageStoreDeprecated mockRepository;

    @BeforeEach
    void beforeEach() {
        this.packageManager = new PackageManager(packageRegistry, packageStoreDeprecated, mockRepository);
    }

    //   A
    //  / \
    // B  Q
    // \ /
    //  T
    @Test
    void GIVEN_proposed_tree_WHEN_new_device_THEN_decide_package_version() throws PackageVersionConflictException {
        PackageMetadata t1 = new PackageMetadata("T", "2.0.0", ">=1.0.0");
        PackageMetadata b =
                new PackageMetadata("B", "1.0.0", ">=1.0.0", Collections.singleton(t1), Collections.emptySet());
        PackageMetadata t2 = new PackageMetadata("T", "2.0.0", ">=2.0.0");
        PackageMetadata q =
                new PackageMetadata("Q", "1.0.0", ">=1.0.0", Collections.singleton(t2), Collections.emptySet());

        PackageMetadata a = new PackageMetadata("A", "1.0.0", ">=1.0.0", new HashSet<>(Arrays.asList(b, q)),
                Collections.emptySet());

        Map<String, PackageRegistryEntry> devicePackages = new HashMap<>();
        packageManager.resolveDependencies(a, devicePackages);

        assertThat(devicePackages.size(), is(4));
        assertThat(devicePackages.get("A").getVersion().getValue(), is("1.0.0"));
        assertThat(devicePackages.get("A").getDependsOnBy().size(), is(0));
        assertThat(devicePackages.get("A").getDependsOn().size(), is(2));
        assertThat(devicePackages.get("A").getDependsOn(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("B", new Semver("1.0.0"), ">=1.0.0")));
        assertThat(devicePackages.get("A").getDependsOn(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("Q", new Semver("1.0.0"), ">=1.0.0")));

        assertThat(devicePackages.get("B").getVersion().getValue(), is("1.0.0"));
        assertThat(devicePackages.get("B").getDependsOn(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("T", new Semver("2.0.0"), ">=1.0.0")));
        assertThat(devicePackages.get("B").getDependsOnBy(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("A", new Semver("1.0.0"), ">=1.0.0")));

        assertThat(devicePackages.get("Q").getVersion().getValue(), is("1.0.0"));
        assertThat(devicePackages.get("Q").getDependsOn(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("T", new Semver("2.0.0"), ">=2.0.0")));
        assertThat(devicePackages.get("Q").getDependsOnBy(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("A", new Semver("1.0.0"), ">=1.0.0")));

        assertThat(devicePackages.get("T").getVersion().getValue(), is("2.0.0"));
        assertThat(devicePackages.get("T").getDependsOnBy(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("B", new Semver("1.0.0"), ">=1.0.0")));
        assertThat(devicePackages.get("T").getDependsOnBy(),
                IsMapContaining.hasValue(new PackageRegistryEntry.Reference("Q", new Semver("1.0.0"), ">=2.0.0")));
        assertThat(devicePackages.get("T").getDependsOn().size(), is(0));
    }

    // To be add more unit tests

    // TODO: This should mock the local/source repositories
    @Test
    void GIVEN_package_entries_WHEN_download_request_THEN_packages_downloaded()
            throws IOException, PackagingException, URISyntaxException {

        this.packageManager = new PackageManager(new PackageRegistryImpl(), TestHelper.getPathForLocalTestCache(),
                TestHelper.getPathForMockRepository());

        PackageRegistryEntry logEntry =
                new PackageRegistryEntry(TestHelper.LOG_PACKAGE_NAME, new Semver("1.0.0"), null);
        PackageRegistryEntry monitorEntry =
                new PackageRegistryEntry(TestHelper.COOL_DB_PACKAGE_NAME, new Semver("1.0.0"), null);

        Set<PackageRegistryEntry> entries = new HashSet<>();
        entries.add(logEntry);
        entries.add(monitorEntry);

        Set<PackageRegistryEntry> downloadOut = packageManager.downloadPackages(entries);
        assertEquals(2, downloadOut.size());
        assertThat(downloadOut, hasItems(logEntry, monitorEntry));

        Path logOutPath = TestHelper.getPathForLocalTestCache().resolve(TestHelper.LOG_PACKAGE_NAME).resolve("1.0.0");
        Path coolDBOutPath =
                TestHelper.getPathForLocalTestCache().resolve(TestHelper.COOL_DB_PACKAGE_NAME).resolve("1.0.0");
        assertTrue(Files.exists(logOutPath));
        assertTrue(Files.exists(logOutPath.resolve("recipe.yaml")));
        assertTrue(Files.exists(coolDBOutPath));
        assertTrue(Files.exists(coolDBOutPath.resolve("recipe.yaml")));
    }

    // package tree in registry
    //   A
    //  / \
    // B  c
    // | /
    //  D
    @Test
    void GIVEN_packages_correctly_registered_WHEN_load_package_by_target_name_THEN_return_package_tree()
            throws Exception {
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());
        PackageRegistryEntry entryB = new PackageRegistryEntry("B", new Semver("1.0.0"), Collections.emptyMap());
        PackageRegistryEntry entryC = new PackageRegistryEntry("C", new Semver("1.0.0"), Collections.emptyMap());
        PackageRegistryEntry entryD = new PackageRegistryEntry("D", new Semver("1.0.0"), Collections.emptyMap());

        entryA.getDependsOn().put("B", new PackageRegistryEntry.Reference("B", new Semver("1.0.0"), ">=1.0.0"));
        entryA.getDependsOn().put("C", new PackageRegistryEntry.Reference("C", new Semver("1.0.0"), ">=1.0.0"));
        entryB.getDependsOn().put("D", new PackageRegistryEntry.Reference("D", new Semver("1.0.0"), ">=1.0.0"));
        entryC.getDependsOn().put("D", new PackageRegistryEntry.Reference("D", new Semver("1.0.0"), ">=1.0.0"));

        Map<String, PackageRegistryEntry> activePackages = new HashMap<>();
        activePackages.put("A", entryA);
        activePackages.put("B", entryB);
        activePackages.put("C", entryC);
        activePackages.put("D", entryD);

        Package pkgA = new Package(null, "A", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStoreDeprecated.getPackage("A", new Semver("1.0.0"))).thenReturn(Optional.of(pkgA));
        Package pkgB = new Package(null, "B", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStoreDeprecated.getPackage("B", new Semver("1.0.0"))).thenReturn(Optional.of(pkgB));
        Package pkgC = new Package(null, "C", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStoreDeprecated.getPackage("C", new Semver("1.0.0"))).thenReturn(Optional.of(pkgC));
        Package pkgD = new Package(null, "D", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStoreDeprecated.getPackage("D", new Semver("1.0.0"))).thenReturn(Optional.of(pkgD));
        Package pkg = packageManager.loadPackage("A", null, activePackages, new HashMap<>());

        assertThat(pkg.getPackageName(), is("A"));
        assertThat(pkg.getDependencyPackages().size(), is(2));
        assertThat(pkg.getDependencyPackages().contains(pkgB), is(true));
        assertThat(pkg.getDependencyPackages().contains(pkgC), is(true));
        pkg = pkg.getDependencyPackages().stream().filter(p -> p.getPackageName().equals("B")).findAny().orElse(null);
        assertThat(pkg, notNullValue());
        assertThat(pkg.getDependencyPackages().size(), is(1));
        assertThat(pkg.getDependencyPackages().contains(pkgD), is(true));
    }

    @Test
    void GIVEN_packages_registered_WHEN_load_package_from_store_THEN_store_throw_exception() throws Exception {
        when(packageStoreDeprecated.getPackage(anyString(), any())).thenThrow(new IOException());
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());

        assertThrows(PackageLoadingException.class,
                () -> packageManager.loadPackage("A", null, Collections.singletonMap("A", entryA), new HashMap<>()),
                "failed to load package A from package store");
    }

    @Test
    void GIVEN_packages_registered_WHEN_load_package_from_store_THEN_store_return_nothing() throws Exception {
        when(packageStoreDeprecated.getPackage(anyString(), any())).thenReturn(Optional.empty());
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());

        assertThrows(PackageLoadingException.class,
                () -> packageManager.loadPackage("A", null, Collections.singletonMap("A", entryA), new HashMap<>()),
                "package A not found");
    }

    @Test
    void GIVEN_packages_not_registered_WHEN_load_package_THEN_fail_to_proceed() {
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());

        assertThrows(PackageLoadingException.class,
                () -> packageManager.loadPackage("B", null, Collections.singletonMap("A", entryA), new HashMap<>()),
                "package B not found in registry");
    }

    @Test
    void GIVEN_deployment_sets_parameters_WHEN_load_package_invoked_THEN_plug_in_new_parameter_values()
            throws Exception {
        // GIVEN
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());
        PackageRegistryEntry entryB = new PackageRegistryEntry("B", new Semver("1.0.0"), Collections.emptyMap());
        PackageRegistryEntry entryC = new PackageRegistryEntry("C", new Semver("1.0.0"), Collections.emptyMap());

        entryA.getDependsOn().put("B", new PackageRegistryEntry.Reference("B", new Semver("1.0.0"), "1.0.0"));
        entryA.getDependsOn().put("C", new PackageRegistryEntry.Reference("C", new Semver("1.0.0"), "1.0.0"));
        List<PackageRegistryEntry> activePackages = Arrays.asList(entryA, entryB, entryC);
        when(packageRegistry.findActivePackages()).thenReturn(activePackages);


        Set<PackageParameter> packageAParams = new HashSet<>(
                Arrays.asList(new PackageParameter("PackageA_param1", "PackageA_param1_default", "String"),
                        new PackageParameter("PackageA_param2", "PackageA_param2_default", "String")));
        Set<PackageParameter> packageBParams = new HashSet<>(
                Arrays.asList(new PackageParameter("PackageB_param1", "PackageB_param1_default", "String"),
                        new PackageParameter("PackageB_param2", "PackageB_param2_default", "String")));
        Set<PackageParameter> packageCParams = new HashSet<>(
                Arrays.asList(new PackageParameter("PackageC_param1", "PackageC_param1_default", "String"),
                        new PackageParameter("PackageC_param2", "PackageC_param2_default", "String")));

        Package pkgA = new Package(null, "A", new Semver("1.0.0"), null, null, packageAParams, null, null, null, null);
        when(packageStoreDeprecated.getPackage("A", new Semver("1.0.0"))).thenReturn(Optional.of(pkgA));
        Package pkgB = new Package(null, "B", new Semver("1.0.0"), null, null, packageBParams, null, null, null, null);
        when(packageStoreDeprecated.getPackage("B", new Semver("1.0.0"))).thenReturn(Optional.of(pkgB));
        Package pkgC = new Package(null, "C", new Semver("1.0.0"), null, null, packageCParams, null, null, null, null);
        when(packageStoreDeprecated.getPackage("C", new Semver("1.0.0"))).thenReturn(Optional.of(pkgC));

        PackageMetadata pkgMetadatsB = new PackageMetadata("B", "1.0.0", "1.0.0", Collections.emptySet(),
                Collections.singleton(new PackageParameter("PackageB_param1", "PackageB_param1_value", "String")));
        PackageMetadata pkgMetadatsC =
                new PackageMetadata("C", "1.0.0", "1.0.0", Collections.emptySet(), Collections.emptySet());
        PackageMetadata pkgMetadatsA =
                new PackageMetadata("A", "1.0.0", "1.0.0", new HashSet<>(Arrays.asList(pkgMetadatsB, pkgMetadatsC)),
                        new HashSet<>(Arrays.asList(
                                new PackageParameter("PackageA_param1", "PackageA_param1_value", "String"),
                                new PackageParameter("PackageA_param2", "PackageA_param2_value", "String"))));

        // WHEN
        Set<Package> resolvedPackages = packageManager.resolvePackages(Collections.singleton(pkgMetadatsA)).get();
        Package resultPackageA = findPackageInSet(resolvedPackages, "A");
        Package resultPackageB = findPackageInSet(resultPackageA.getDependencyPackages(), "B");
        Package resultPackageC = findPackageInSet(resultPackageA.getDependencyPackages(), "C");

        // THEN
        assertThat("If all param values were overridden, all should be loaded into package",
                findPackageParameterValue(resultPackageA.getPackageParameters(), "PackageA_param1")
                        .equals("PackageA_param1_value"));
        assertThat("If all param values were overridden, all should be loaded into package",
                findPackageParameterValue(resultPackageA.getPackageParameters(), "PackageA_param2")
                        .equals("PackageA_param2_value"));
        assertThat("If some param values were overridden, only those should be loaded into package",
                findPackageParameterValue(resultPackageB.getPackageParameters(), "PackageB_param1")
                        .equals("PackageB_param1_value"));
        assertThat("If some param values were overridden, rest should retain defaults",
                findPackageParameterValue(resultPackageB.getPackageParameters(), "PackageB_param2")
                        .equals("PackageB_param2_default"));
        assertThat("If no param values were overridden, defaults should be retained",
                findPackageParameterValue(resultPackageC.getPackageParameters(), "PackageC_param1")
                        .equals("PackageC_param1_default"));
        assertThat("If no param values were overridden, defaults should be retained",
                findPackageParameterValue(resultPackageC.getPackageParameters(), "PackageC_param2")
                        .equals("PackageC_param2_default"));
    }

    private Package findPackageInSet(Set<Package> packages, String name) {
        return packages.stream().filter(pkg -> pkg.getPackageName().equals(name)).findAny().orElse(null);
    }

    private String findPackageParameterValue(Set<PackageParameter> params, String name) {
        PackageParameter match = params.stream().filter(param -> param.getName().equals(name)).findAny().orElse(null);
        return match.getValue();
    }
}
