package com.aws.iot.evergreen.packagemanager.models;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class PackageMetadataTest {

    @Test
    void GIVEN_package_metadata_WHEN_equality_is_called_THEN_decide_if_they_are_equal() {
        PackageMetadata package1 = new PackageMetadata("PkgA", "1.3.4", ">1.0.0", Collections.emptySet(), Collections.emptySet());
        PackageMetadata package2 = new PackageMetadata("PkgA", "1.2.3", ">1.0.0", Collections.emptySet(), Collections.emptySet());
        PackageMetadata package3 = new PackageMetadata("PkgA", "1.3.4", ">1.0.0", Collections.singleton(package1), Collections.emptySet());
        PackageMetadata package4 = new PackageMetadata("PkgA", "1.3.4", "<2.0.0");

        assertThat(package1, not(package2));
        assertThat(package1, is(package3));
        assertThat(package1, not(package4));
    }

}
