package com.aws.iot.evergreen.packagemanagement.model;


import com.vdurmont.semver4j.Semver;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class PackageMetaDataTest {

    @Test
    void testPackageMetaDataEquality() {
        PackageMetaData package1 = new PackageMetaData("PkgA", new Semver("1.3.4"), Collections.emptySet(),
                Collections.emptySet());
        PackageMetaData package2 = new PackageMetaData("PkgA", new Semver("1.2.3"), Collections.emptySet(),
                Collections.emptySet());
        PackageMetaData package3 = new PackageMetaData("PkgA", new Semver("1.3.4"), Collections.emptySet(),
                Collections.singleton(package1));

        assertThat(package1, not(package2));
        assertThat(package1, is(package3));
    }

}
