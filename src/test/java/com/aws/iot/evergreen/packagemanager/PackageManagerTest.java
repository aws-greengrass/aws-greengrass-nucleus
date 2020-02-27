package com.aws.iot.evergreen.packagemanager;

import com.aws.iot.evergreen.packagemanager.exceptions.PackageLoadingException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.exceptions.PackagingException;
import com.aws.iot.evergreen.packagemanager.models.Package;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;
import com.aws.iot.evergreen.packagemanager.plugins.PackageStore;
import com.vdurmont.semver4j.Semver;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
    private PackageStore packageStore;

    @Mock
    private PackageStore mockRepository;

    @BeforeEach
    void beforeEach() {
        this.packageManager = new PackageManager(packageRegistry, packageStore, mockRepository);
    }

    //   A
    //  / \
    // B  Q
    // \ /
    //  T
    @Test
    void GIVEN_proposed_tree_WHEN_new_device_THEN_decide_package_version() throws PackageVersionConflictException {
        PackageMetadata T1 = new PackageMetadata("T", "2.0.0", ">=1.0.0");
        PackageMetadata B = new PackageMetadata("B", "1.0.0", ">=1.0.0", Collections.singleton(T1));
        PackageMetadata T2 = new PackageMetadata("T", "2.0.0", ">=2.0.0");
        PackageMetadata Q = new PackageMetadata("Q", "1.0.0", ">=1.0.0", Collections.singleton(T2));
        PackageMetadata A = new PackageMetadata("A", "1.0.0", ">=1.0.0", new HashSet<>(Arrays.asList(B, Q)));

        Map<String, PackageRegistryEntry> devicePackages = new HashMap<>();
        packageManager.resolveDependencies(A, devicePackages);

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
        when(packageStore.getPackage("A", new Semver("1.0.0"))).thenReturn(Optional.of(pkgA));
        Package pkgB = new Package(null, "B", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStore.getPackage("B", new Semver("1.0.0"))).thenReturn(Optional.of(pkgB));
        Package pkgC = new Package(null, "C", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStore.getPackage("C", new Semver("1.0.0"))).thenReturn(Optional.of(pkgC));
        Package pkgD = new Package(null, "D", new Semver("1.0.0"), null, null, null, null, null, null, null);
        when(packageStore.getPackage("D", new Semver("1.0.0"))).thenReturn(Optional.of(pkgD));
        Package pkg = packageManager.loadPackage("A", activePackages, new HashMap<>());

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
        when(packageStore.getPackage(anyString(), any())).thenThrow(new IOException());
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());

        assertThrows(PackageLoadingException.class,
                () -> packageManager.loadPackage("A", Collections.singletonMap("A", entryA), new HashMap<>()),
                "failed to load package A from package store");
    }

    @Test
    void GIVEN_packages_registered_WHEN_load_package_from_store_THEN_store_return_nothing() throws Exception {
        when(packageStore.getPackage(anyString(), any())).thenReturn(Optional.empty());
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());

        assertThrows(PackageLoadingException.class,
                () -> packageManager.loadPackage("A", Collections.singletonMap("A", entryA), new HashMap<>()),
                "package A not found");
    }

    @Test
    void GIVEN_packages_not_registered_WHEN_load_package_THEN_fail_to_proceed() {
        PackageRegistryEntry entryA = new PackageRegistryEntry("A", new Semver("1.0.0"), Collections.emptyMap());

        assertThrows(PackageLoadingException.class,
                () -> packageManager.loadPackage("B", Collections.singletonMap("A", entryA), new HashMap<>()),
                "package B not found in registry");
    }
}
