package com.aws.iot.evergreen.packagemanager.models;

import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class PackageRegistryEntryTest {

    @Test
    void GIVEN_package_registry_entry_WHEN_equality_is_called_THEN_decide_if_they_are_equal() {
        PackageRegistryEntry entry1 = new PackageRegistryEntry("A", new Semver("1.2.3"), Collections.singletonMap("P",
                new PackageRegistryEntry.Reference("P", new Semver("1.4.5"), ">=1.0.0")));
        PackageRegistryEntry entry2 = new PackageRegistryEntry("A", new Semver("1.3.3"), Collections.emptyMap());
        PackageRegistryEntry entry3 = new PackageRegistryEntry("A", new Semver("1.2.3"), Collections.emptyMap());

        assertThat(entry1, not(entry2));
        assertThat(entry1, is(entry3));
    }
}
