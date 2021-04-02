/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.util.FileSystemPermission;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.nio.file.Path;

/**
 * Utility matchers for testing.
 */
public final class Matchers {

    private Matchers() {}

    /**
     * Matcher for validating that a Path has a set of expected Posix file permissions.
     *
     * @param expected the permisssions to expect
     * @return a matcher that will validate permissions.
     */
    @SuppressWarnings("PMD.LinguisticNaming")
    public static Matcher<Path> hasPermission(FileSystemPermission expected) {
        return new TypeSafeDiagnosingMatcher<Path>() {
            @Override
            protected boolean matchesSafely(Path path, Description description) {
                return PlatformTestUtils.getInstance().hasPermission(expected, path);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("permissions are ").appendText(expected.toString());
            }
        };
    }
}
