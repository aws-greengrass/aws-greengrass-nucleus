/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static com.aws.greengrass.util.Coerce.appendParseableString;
import static com.aws.greengrass.util.Coerce.toBoolean;
import static com.aws.greengrass.util.Coerce.toDouble;
import static com.aws.greengrass.util.Coerce.toEnum;
import static com.aws.greengrass.util.Coerce.toInt;
import static com.aws.greengrass.util.Coerce.toObject;
import static com.aws.greengrass.util.Coerce.toStringArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
class CoerceTest {
    @Mock
    Context mockContext;

    @Test
    void testToBoolean() {
        assertTrue(toBoolean(true));
        assertTrue(toBoolean("true"));
        assertTrue(toBoolean(1));
        assertTrue(toBoolean("yes"));
        assertTrue(toBoolean(99));
        assertFalse(toBoolean(false));
        assertFalse(toBoolean("false"));
        assertFalse(toBoolean("bozo"));
        assertFalse(toBoolean(null));
        assertFalse(toBoolean(0));

        Topic testTopic = Topic.of(mockContext, "test", true);
        assertTrue(toBoolean(testTopic));
        assertFalse(toBoolean(testTopic.withValue(false)));
    }

    @Test
    void testToInt() {
        assertEquals(42, toInt(42));
        assertEquals(42, toInt(42.0));
        assertEquals(42, toInt(42.1));
        assertEquals(42, toInt("42"));
        assertEquals(0, toInt("null"));
        assertEquals(0, toInt(null));
        assertEquals(0, toInt(false));
        assertEquals(1, toInt(true));
        assertEquals(42, toInt(Topic.of(mockContext, "test", 42)));
    }

    @Test
    void testToDouble() {
        assertEquals(42.0, toDouble(42), 1e-10);
        assertEquals(3.14159, toDouble(3.14159), 1e-10);
        assertEquals(3.14159, toDouble("3.14159"), 1e-10);
        assertEquals(42.0, toDouble(Topic.of(mockContext, "test", 42)), 1e-10);
        assertEquals(0.0, toDouble(false), 1e-10);
        assertEquals(1.0, toDouble(true), 1e-10);
        assertEquals(0.0, toDouble("null"), 1e-10);
        assertEquals(0.0, toDouble(null), 1e-10);
    }

    @Test
    void testToEnum() {
        assertEquals(en.Green, toEnum(en.class, en.Green));
        assertEquals(en.Green, toEnum(en.class, 1));
        assertEquals(en.Green, toEnum(en.class, "Green"));
        assertEquals(en.Green, toEnum(en.class, "g"));
        assertEquals(en.Green, toEnum(en.class, "greE"));
        assertEquals(en.Gross, toEnum(en.class, "gro"));
        assertNull(toEnum(en.class, "grok"));
    }

    @Test
    void testToStringArray() {
        t("");
        t("[]");
        t("[  ]");
        t("[foo, bar, baz]", "foo", "bar", "baz");
        t("foo, bar, baz]", "foo", "bar", "baz]");
        t("foo", "foo");
        t("foo, bar, baz", "foo", "bar", "baz");
        t("foo , bar, baz ", "foo", "bar", "baz");
        t("foo,  bar, baz", "foo", "bar", "baz");
        t("foo, , baz", "foo", "", "baz");
        t(Topic.of(mockContext, "test", null));
        t(new String[] {
                "foo", "bar", "baz"
        }, "foo", "bar", "baz");
        t(new int[] {
                1, 2, 3
        }, "1", "2", "3");
    }

    void t(Object s, String... expect) {
        String[] t = toStringArray(s);
        int len = t.length;
        assertEquals(len, expect.length);
        for (int i = 0; i < len; i++) {
            assertEquals(expect[i], t[i]);
        }
    }

    @Test
    void testToObject() throws JsonProcessingException {
        assertThat((List<String>) toObject("[]"), is(empty()));
        assertThat((List<String>) toObject("[  ]"), is(empty()));
        assertThat((List<String>) toObject("[\"foo\", \"bar\", \"baz\"]"), containsInAnyOrder("foo", "bar", "baz"));
        assertThat((List<String>) toObject("[ \"foo\", \"bar\", \"baz\" ]"), containsInAnyOrder("foo", "bar", "baz"));
        assertThat((List<String>) toObject("[\"foo1\", \"bar_2\", \"baz-3\"]"),
                containsInAnyOrder("foo1", "bar_2", "baz-3"));
        assertNull(toObject("null"));
        assertEquals("", toObject(null));
        assertEquals("", toObject(""));
        assertEquals(12.34, toObject("12.34"));
        assertEquals(1234, toObject("1234"));
        assertEquals(1234567890123L, toObject("1234567890123"));
    }

    @Test
    void testAppendParseableString() throws IOException {
        StringBuilder sb = new StringBuilder();
        appendParseableString(Topic.of(mockContext, "test", null), sb);
        assertEquals("null\n", sb.toString());
    }

    enum en {
        Red, Green, Blue, Gross
    }
}
