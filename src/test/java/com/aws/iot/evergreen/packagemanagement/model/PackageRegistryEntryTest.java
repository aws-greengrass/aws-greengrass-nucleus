package com.aws.iot.evergreen.packagemanagement.model;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class PackageRegistryEntryTest {

    @Test
    void testEquality() {
        PackageRegistryEntry entry1 = new PackageRegistryEntry("A", "1.2.3");
        PackageRegistryEntry entry2 = new PackageRegistryEntry("A", "1.3.3");
        PackageRegistryEntry entry3 = new PackageRegistryEntry("A", "1.2.3");

        entry3.getDependOn().add(new PackageRegistryEntry.Reference("P", "1.4.5", ">=1.0.0"));
        assertThat(entry1, not(entry2));
        assertThat(entry1, is(entry3));
    }
}
