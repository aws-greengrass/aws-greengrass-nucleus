/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import org.junit.jupiter.api.Test;

import static com.aws.iot.evergreen.util.Coerce.toBoolean;
import static com.aws.iot.evergreen.util.Coerce.toDouble;
import static com.aws.iot.evergreen.util.Coerce.toInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CoerceTest {

    @Test
    public void T1() {
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
    }

    @Test
    public void T2() {
        assertEquals(42, toInt(42));
        assertEquals(42, toInt(42.0));
        assertEquals(42, toInt(42.1));
        assertEquals(42, toInt("42"));
        assertEquals(42.0, toDouble(42), 1e-10);
        assertEquals(3.14159, toDouble(3.14159), 1e-10);
        assertEquals(3.14159, toDouble("3.14159"), 1e-10);
    }

    @Test
    public void T3() {
        assertEquals("\"xx\"", Coerce.toQuotedString("xx"));
        assertEquals("\"x\\nx\"", Coerce.toQuotedString("x\nx"));
        assertEquals("\"x\\nx\\u0022\"", Coerce.toQuotedString("x\nx\""));
    }

    @Test
    public void T4() {
        assertEquals(en.Green, Coerce.toEnum(en.class, en.Green));
        assertEquals(en.Green, Coerce.toEnum(en.class, 1));
        assertEquals(en.Green, Coerce.toEnum(en.class, "Green"));
        assertEquals(en.Green, Coerce.toEnum(en.class, "g"));
        assertEquals(en.Green, Coerce.toEnum(en.class, "greE"));
        assertEquals(en.Gross, Coerce.toEnum(en.class, "gro"));
        assertNull(Coerce.toEnum(en.class, "grok"));
    }

    @Test
    public void T5() {
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
    }

    void t(String T, String... expect) {
        String[] t = Coerce.toStringArray(T);
        int len = t.length;
        assertEquals(len, expect.length);
        for (int i = 0; i < len; i++) {
            assertEquals(expect[i], t[i]);
        }
    }

    enum en {Red, Green, Blue, Gross}
}
