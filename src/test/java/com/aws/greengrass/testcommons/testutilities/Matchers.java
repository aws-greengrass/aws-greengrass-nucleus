/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.util.FileSystemPermission;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeDiagnosingMatcher;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Utility matchers for testing.
 */
public final class Matchers {

    private Matchers() {
    }

    /**
     * Matcher for validating that a Path has a set of expected Posix file permissions.
     *
     * @param expected the permisssions to expect
     * @return a matcher that will validate permissions.
     */
    @SuppressWarnings("PMD.LinguisticNaming")
    public static Matcher<Path> hasPermission(FileSystemPermission expected) {
        return new TypeSafeDiagnosingMatcher<Path>() {
            private Path path;

            @Override
            protected boolean matchesSafely(Path path, Description description) {
                this.path = path;
                return PlatformTestUtils.getInstance().hasPermission(expected, path, description);
            }

            @Override
            public void describeTo(Description description) {
                try {
                    description.appendText("Expected ACL ")
                            .appendText(PlatformTestUtils.getInstance().getExpectedAcl(expected, path));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
