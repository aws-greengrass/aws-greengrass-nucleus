package com.aws.iot.evergreen.packagemanagement.model;

import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class PackageMetaDataTest {

    @Test
    void testPackageMetaDataEquality() {
        PackageMetadata package1 = new PackageMetadata("PkgA", "1.3.4", Collections.emptySet(),
                Collections.emptySet());
        PackageMetadata package2 = new PackageMetadata("PkgA", "1.2.3", Collections.emptySet(),
                Collections.emptySet());
        PackageMetadata package3 = new PackageMetadata("PkgA", "1.3.4", Collections.emptySet(),
                Collections.singleton(package1));
        PackageMetadata package4 = new PackageMetadata("PkgA", "1.3.4");

        assertThat(package1, not(package2));
        assertThat(package1, is(package3));
        assertThat(package1, is(package4));
    }

}
