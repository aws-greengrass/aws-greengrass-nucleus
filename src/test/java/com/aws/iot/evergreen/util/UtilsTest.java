/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.aws.iot.evergreen.util.Utils.deepToString;
import static com.aws.iot.evergreen.util.Utils.dequote;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class UtilsTest {

    private static void testLparse1(String s, long expecting, String remaining) {
        CharBuffer cb = CharBuffer.wrap(s);
        long v = Utils.parseLong(cb);
        assertEquals(expecting, v, s);
        assertEquals(remaining, cb.toString(), s);
    }

    private static void testLparseRadix1(String s, int start, int stop, int radix, long expecting) {
        long v = Utils.parseLongChecked(s, start, stop, radix);
        assertEquals(expecting, v, s);
    }

    @Test
    public void testLparse() {
        testLparse1("   ", 0, "");
        testLparse1("", 0, "");
        testLparse1("12", 12, "");
        testLparse1("0x12", 0x12, "");
        testLparse1("0xA1", 0xA1, "");
        testLparse1("0xBf", 0xbF, "");
        testLparse1("012", 012, "");
        testLparse1("0b1012", 5, "2");
        testLparse1("-0b001012", -5, "2");
        testLparse1("-012 k", -012, " k");
        testLparse1("-0x17352k", -0x17352, "k");
        testLparse1("  -  0x17352-k", -0x17352, "-k");
    }

    @Test
    public void testLparseRadix() {
        testLparseRadix1("123", 0, 3, 16, 0x123);
        testLparseRadix1("123", 0, 2, 16, 0x12);
        testLparseRadix1("123", 1, 3, 16, 0x23);
        testLparseRadix1("12AX", 0, 3, 16, 0x12A);
        testLparseRadix1("012", 0, 2, 8, 01);
        testLparseRadix1("012", 0, 3, 8, 012);
    }

    @Test
    public void testLparseBadSize() {
        assertThrows(IndexOutOfBoundsException.class, () -> testLparseRadix1("12", 0, 3, 16, -1));
    }

    @Test
    public void testLparseBadLead() {
        assertThrows(NumberFormatException.class, () -> testLparseRadix1("X12", 0, 3, 16, -1));
    }

    @Test
    public void testLparseBadTail() {
        assertThrows(NumberFormatException.class, () -> testLparseRadix1("12X", 0, 3, 16, -1));
    }

    @Test
    public void T2() {
        Map m = new LinkedHashMap();
        m.put("CDC", "6400");
        m.put("PDP", "8/I");
        Object[] o = {5, "hello", new HashMap(), m};
        assertEquals("[5,\"hello\",{},{CDC:\"6400\",PDP:\"8/I\"}]", deepToString(o, 80).toString());
        assertEquals("[5,\"hello\"...]", deepToString(o, 6).toString());
        assertEquals("[5,\"hello\",{},{CDC:\"6400\"...}]", deepToString(o, 20).toString());
    }

    @Test
    public void T3() {
        assertEquals("foo", dequote("foo"));
        assertEquals("foo\nbar", dequote("\"foo\\nbar\""));
        assertEquals("foo\nbar", dequote("\"foo\\u000Abar\""));
    }

    @Test
    public void immutableMapRead() {
        Map<String, Integer> map = Utils.immutableMap("a", 1, "b", 2, "c", 3);
        assertThat(map.get("a"), is(equalTo(1)));
        assertThat(map.get("b"), is(equalTo(2)));
        assertThat(map.get("c"), is(equalTo(3)));
    }

    @Test
    public void immutableMapOddParams() {
        assertThrows(IllegalArgumentException.class, () -> Utils.immutableMap("a", 1, "b", 2, "c"));
    }

    @Test
    public void immutableMapWrite() {
        Map<String, Integer> map = Utils.immutableMap("a", 1);
        assertThat(map.get("a"), is(equalTo(1)));
        assertThrows(UnsupportedOperationException.class, () -> map.put("b", 4));
    }

}
