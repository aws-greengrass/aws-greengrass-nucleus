package com.aws.iot.evergreen.packagemanager;


import com.aws.iot.evergreen.packagemanager.exceptions.PackageVersionConflictException;
import com.aws.iot.evergreen.packagemanager.models.PackageMetadata;
import com.aws.iot.evergreen.packagemanager.models.PackageRegistryEntry;
import com.vdurmont.semver4j.Semver;
import org.hamcrest.collection.IsMapContaining;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

class PackageManagerTest {

    private PackageManager packageManager = new PackageManager(new PackageRegistryImpl());

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

}
