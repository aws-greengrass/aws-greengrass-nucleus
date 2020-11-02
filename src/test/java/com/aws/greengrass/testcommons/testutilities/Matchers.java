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
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

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
        Set<PosixFilePermission> perms = expected.toPosixFilePermissions();
        return new TypeSafeDiagnosingMatcher<Path>() {
            @Override
            protected boolean matchesSafely(Path path, Description description) {
                PosixFileAttributeView view =
                        Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);

                if (view == null) {
                    description.appendText("does not have PosixFileAttributeView");
                    return false;
                }

                try {
                    Set<PosixFilePermission> actual = view.readAttributes().permissions();
                    description.appendText("posix permissions are ").appendText(PosixFilePermissions.toString(actual));
                    description.appendText(" for path ").appendValue(path);
                    return actual.containsAll(perms) && perms.containsAll(actual);
                } catch (IOException e) {
                    description.appendText("encountered IOException ").appendValue(e);
                    return false;
                }
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("posix permissions are ").appendText(PosixFilePermissions.toString(perms));
            }
        };
    }
}
