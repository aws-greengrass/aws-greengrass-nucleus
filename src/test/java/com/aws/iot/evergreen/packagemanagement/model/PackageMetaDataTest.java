package com.aws.iot.evergreen.packagemanagement.model;

import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class PackageMetaDataTest {

    @Test
    void testPackageMetaDataEquality() {
        PackageMetadata package1 = new PackageMetadata("PkgA", "1.3.4", ">1.0.0",
                Collections.emptySet());
        PackageMetadata package2 = new PackageMetadata("PkgA", "1.2.3", ">1.0.0",
                Collections.emptySet());
        PackageMetadata package3 = new PackageMetadata("PkgA", "1.3.4", ">1.1.1",
                Collections.singleton(package1));
        PackageMetadata package4 = new PackageMetadata("PkgA", "1.3.4", "<2.0.0");

        assertThat(package1, not(package2));
        assertThat(package1, is(package3));
        assertThat(package1, is(package4));
    }

}
