/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.dependency.Context;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static com.aws.iot.evergreen.util.Coerce.appendParseableString;
import static com.aws.iot.evergreen.util.Coerce.removed;
import static com.aws.iot.evergreen.util.Coerce.toBoolean;
import static com.aws.iot.evergreen.util.Coerce.toDouble;
import static com.aws.iot.evergreen.util.Coerce.toEnum;
import static com.aws.iot.evergreen.util.Coerce.toInt;
import static com.aws.iot.evergreen.util.Coerce.toObject;
import static com.aws.iot.evergreen.util.Coerce.toQuotedString;
import static com.aws.iot.evergreen.util.Coerce.toStringArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith({EGExtension.class, MockitoExtension.class})
public class CoerceTest {
    @Mock
    Context mockContext;

    @Test
    public void testToBoolean() {
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
    public void testToInt() {
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
    public void testToDouble() {
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
    public void testToQuotedString() {
        assertEquals("\"xx\"", toQuotedString("xx"));
        assertEquals("\"x\\nx\"", toQuotedString("x\nx"));
        assertEquals("\"x\\nx\\u0022\"", toQuotedString("x\nx\""));
        assertEquals("\"abc\"", toQuotedString(Topic.of(mockContext, "test", "abc")));
        assertEquals("\"abc\"", toQuotedString(Topic.of(mockContext, "test",
                Topic.of(mockContext, "nested", "abc"))));
        assertEquals("\"\"", toQuotedString(null));
        assertEquals("\"removed\"", toQuotedString(removed));
        assertEquals("\"x\\r\\tx\"", toQuotedString("x\r\tx"));
    }

    @Test
    public void testToEnum() {
        assertEquals(en.Green, toEnum(en.class, en.Green));
        assertEquals(en.Green, toEnum(en.class, 1));
        assertEquals(en.Green, toEnum(en.class, "Green"));
        assertEquals(en.Green, toEnum(en.class, "g"));
        assertEquals(en.Green, toEnum(en.class, "greE"));
        assertEquals(en.Gross, toEnum(en.class, "gro"));
        assertNull(toEnum(en.class, "grok"));
    }

    @Test
    public void testToStringArray() {
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
        t(new String[]{"foo", "bar", "baz"}, "foo", "bar", "baz");
        t(new int[]{1, 2, 3}, "1", "2", "3");
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
    public void testToObject() {
        assertThat((List<String>) toObject("[]"), is(empty()));
        assertThat((List<String>) toObject("[  ]"), is(empty()));
        assertThat((List<String>) toObject("[foo, bar, baz]"), containsInAnyOrder("foo", "bar", "baz"));
        assertThat((List<String>) toObject("[ foo, bar, baz ]"), containsInAnyOrder("foo", "bar", "baz"));
        assertThat((List<String>) toObject("[foo1, bar_2, baz-3]"), containsInAnyOrder("foo1", "bar_2", "baz-3"));
        assertThat(toObject("foo, bar, baz]"), is(equalTo("foo, bar, baz]")));
        assertThat(toObject("[foo, bar, baz"), is(equalTo("[foo, bar, baz")));
        assertThat(toObject("foo, bar, baz"), is(equalTo("foo, bar, baz")));
        assertNull(toObject("null"));
        assertEquals("", toObject(null));
        assertEquals("", toObject(""));
        assertEquals(12.34, toObject("12.34"));
        assertEquals(1234, toObject("1234"));
        assertEquals(1234567890123L, toObject("1234567890123"));
    }

    @Test
    public void testAppendParseableString() throws IOException {
        StringBuilder sb = new StringBuilder();
        appendParseableString(Topic.of(mockContext, "test", null), sb);
        assertEquals("null", sb.toString());
    }

    enum en {Red, Green, Blue, Gross}
}
