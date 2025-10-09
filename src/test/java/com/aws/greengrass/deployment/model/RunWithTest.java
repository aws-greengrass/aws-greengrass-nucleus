/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@ExtendWith(GGExtension.class)
class RunWithTest {
    static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @MethodSource("runWithValues")
    void testRunWithValues(String json, boolean hasPosixUserValue, String expectedPosixUser,
            boolean hasWindowsUserValue, String expectedWindowsUser) throws JsonProcessingException {
        RunWith actual = MAPPER.readValue(json, RunWith.class);
        assertThat(actual, is(not(nullValue())));
        assertThat(actual.hasPosixUserValue(), is(hasPosixUserValue));
        if (hasPosixUserValue) {
            assertThat(actual.getPosixUser(), is(expectedPosixUser));
        } else {
            assertThat(actual.getPosixUser(), is(nullValue()));
        }
        assertThat(actual.hasWindowsUserValue(), is(hasWindowsUserValue));
        if (hasWindowsUserValue) {
            assertThat(actual.getWindowsUser(), is(expectedWindowsUser));
        }
    }

    static String quote(String value) {
        if (value != null) {
            return "\"" + value + "\"";
        }
        return value;
    }

    static String json(String posixUser, String windowsUser) {
        return String.format("{\"PosixUser\": %s, \"WindowsUser\": %s }", quote(posixUser), quote(windowsUser));
    }

    static Stream<Arguments> runWithValues() {
        return Stream.of(arguments("{}", false, null, false, null),
                arguments("{ \"PosixUser\": \"foo:bar\", \"systemResourceLimits\": {\"cpus\": 1.5, \"memory\": "
                        + "102400}}", true, "foo:bar", false, null),
                arguments("{ \"WindowsUser\": \"foo\" }", false, null, true, "foo"),
                arguments(json("foo:bar", "foo"), true, "foo:bar", true, "foo"),
                arguments(json(null, "foo"), true, null, true, "foo"),
                arguments(json("foo:bar", null), true, "foo:bar", true, null),
                arguments(json("", ""), true, "", true, ""));
    }

}
