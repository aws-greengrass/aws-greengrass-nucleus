/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.util.platforms.unix;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.platforms.unix.UnixGroupAttributes;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;
import com.aws.greengrass.util.platforms.unix.UnixUserAttributes;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.text.MatchesPattern.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith(GGExtension.class)
@EnabledOnOs({OS.LINUX, OS.MAC})
class UnixPlatformIntegrationTest {

    UnixPlatform platform;

    @BeforeEach
    void before() {
        platform = new UnixPlatform();
    }

    @Test
    void testLookupCurrentUser() throws IOException {
        UnixUserAttributes user = platform.lookupCurrentUser();
        assertThat(user, is(not(nullValue())));

        assertThat(user.getPrincipalIdentifier(), is(not(user.getPrincipalName())));
        assertThat(user.getPrincipalIdentifier(), matchesPattern("[\\d]+"));
        assertThat(user.getPrincipalName().toLowerCase(), is(SystemUtils.USER_NAME));
        assertThat("has primary gid", user.getPrimaryGID().isPresent(), is(true));
    }

    @ParameterizedTest
    @MethodSource("userParameters")
    void testLookupUser(String userIdOrName, boolean exists) throws IOException {
        UnixUserAttributes user = platform.lookupUserByIdentifier(userIdOrName);
        assertThat(user, is(not(nullValue())));
        System.out.println(userIdOrName);

        assertThat(user.getPrincipalIdentifier(), matchesPattern("[\\d]+"));
        if (exists) {
            assertThat(user.getPrincipalIdentifier(), is(not(user.getPrincipalName())));
            assertThat(user.getPrincipalName().toLowerCase(), matchesPattern("[a-z]+"));
            assertThat("has primary gid", user.getPrimaryGID().isPresent(), is(true));
        } else {
            assertThat(user.getPrincipalName(), is(user.getPrincipalName()));
            assertThat("does not have primary gid", user.getPrimaryGID().isPresent(), is(false));
        }
    }

    @Test
    void testLookupGroupExists() throws IOException {
        UnixGroupAttributes group = platform.lookupGroupByIdentifier("0");
        assertThat(group, is(not(nullValue())));

        assertThat(group.getPrincipalIdentifier(), is(not(group.getPrincipalName())));
        assertThat(group.getPrincipalIdentifier(), is("0"));
        assertThat(group.getGID(), is(0));
        assertThat(group.getPrincipalName().toLowerCase(), matchesPattern("[a-z]+"));
    }

    @Test
    void testLookupUserByNameNotExist() {
        assertThat(platform.userExists(randomString()), is(false));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void testLookupUserByInvalidId(String id) throws IOException {
        assertThrows(IOException.class, () -> platform.lookupUserByIdentifier(id));
    }

    @ParameterizedTest
    @MethodSource("invalidParameters")
    void testLookupGroupByInvalidId(String id) throws IOException {
        assertThrows(IOException.class, () -> platform.lookupGroupByIdentifier(id));
    }

    static Stream<Arguments> invalidParameters() {
        return Stream.of(
                arguments("-10"),
                arguments("$"),
                arguments("1a")
        );
    }

    static Stream<Arguments> userParameters() {
        return Stream.of(
                arguments("0", true),
                arguments(SystemUtils.USER_NAME, true),
                // test an id that should not exist: 2^32 is typical max uid - last two are typically reserved so we
                // subtract 3
                arguments(Long.toString((1l << 32) - 3), false)
        );
    }

    static String randomString() {
        return UUID.randomUUID().toString().replaceAll("-", "");
    }
}
