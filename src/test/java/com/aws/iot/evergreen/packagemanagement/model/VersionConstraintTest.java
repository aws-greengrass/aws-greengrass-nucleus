package com.aws.iot.evergreen.packagemanagement.model;


import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;

class VersionConstraintTest {

    @Test
    void testVersionConstraintEquality() {
        assertThat(new VersionConstraint(VersionConstraint.ComparisonOperator.EQUAL,
                        new Semver("1.2.3")),
                is(new VersionConstraint(VersionConstraint.ComparisonOperator.EQUAL,
                        new Semver("1.2.3"))));

        assertThat(new VersionConstraint(VersionConstraint.ComparisonOperator.LARGER,
                        new Semver("1.2.3")),
                not(new VersionConstraint(VersionConstraint.ComparisonOperator.EQUAL,
                        new Semver("1.2.3"))));

        assertThat(new VersionConstraint(VersionConstraint.ComparisonOperator.EQUAL,
                        new Semver("1.2.3")),
                not(new VersionConstraint(VersionConstraint.ComparisonOperator.EQUAL,
                        new Semver("1.2.4"))));
    }

}
