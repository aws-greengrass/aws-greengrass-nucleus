/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.hamcrest.collection.IsMapWithSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.util.Utils.deepToString;
import static com.aws.greengrass.util.Utils.dequote;
import static com.aws.greengrass.util.Utils.inverseMap;
import static com.aws.greengrass.util.Utils.once;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings({"PMD.AvoidUsingOctalValues", "PMD.MethodNamingConventions"})
@ExtendWith({MockitoExtension.class, GGExtension.class})
class UtilsTest {

    @Mock
    Runnable mockRunnable;

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
    void testLparse() {
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
    void testLparseRadix() {
        testLparseRadix1("123", 0, 3, 16, 0x123);
        testLparseRadix1("123", 0, 2, 16, 0x12);
        testLparseRadix1("123", 1, 3, 16, 0x23);
        testLparseRadix1("12AX", 0, 3, 16, 0x12A);
        testLparseRadix1("012", 0, 2, 8, 01);
        testLparseRadix1("012", 0, 3, 8, 012);
    }

    @Test
    void testLparseBadSize() {
        assertThrows(IndexOutOfBoundsException.class, () -> testLparseRadix1("12", 0, 3, 16, -1));
    }

    @Test
    void testLparseBadLead() {
        assertThrows(NumberFormatException.class, () -> testLparseRadix1("X12", 0, 3, 16, -1));
    }

    @Test
    void testLparseBadTail() {
        assertThrows(NumberFormatException.class, () -> testLparseRadix1("12X", 0, 3, 16, -1));
    }

    @Test
    void T2() {
        Map m = new LinkedHashMap();
        m.put("CDC", "6400");
        m.put("PDP", "8/I");
        Object[] o = {5, "hello", new HashMap(), m};
        assertEquals("[5,\"hello\",{},{CDC:\"6400\",PDP:\"8/I\"}]", deepToString(o, 80).toString());
        assertEquals("[5,\"hello\"...]", deepToString(o, 6).toString());
        assertEquals("[5,\"hello\",{},{CDC:\"6400\"...}]", deepToString(o, 20).toString());
        assertEquals("null", deepToString(null).toString());
        assertEquals("[5,\"hello\",{},{CDC:\"6400\"...}]", deepToString(Arrays.asList(o), 20).toString());
    }

    @Test
    void T3() {
        assertEquals("foo", dequote("foo"));
        assertEquals("foo\nbar", dequote("\"foo\\nbar\""));
        assertEquals("foo\b\r\tbar", dequote("\"foo\\b\\r\\tbar\\uxxxx\""));
        assertEquals("foo\nbar", dequote("\"foo\\u000Abar\""));
    }

    @Test
    void immutableMapRead() {
        Map<String, Integer> map = Utils.immutableMap("a", 1, "b", 2, "c", 3);
        assertThat(map.get("a"), is(equalTo(1)));
        assertThat(map.get("b"), is(equalTo(2)));
        assertThat(map.get("c"), is(equalTo(3)));
    }

    @Test
    void immutableMapOddParams() {
        assertThrows(IllegalArgumentException.class, () -> Utils.immutableMap("a", 1, "b", 2, "c"));
    }

    @Test
    void immutableMapWrite() {
        Map<String, Integer> map = Utils.immutableMap("a", 1);
        assertThat(map.get("a"), is(equalTo(1)));
        assertThrows(UnsupportedOperationException.class, () -> map.put("b", 4));
    }

    @Test
    void testInverseMap() {
        Map<String, Integer> map = Utils.immutableMap("a", 1, "b", 2, "c", 1);
        Map<Integer, List<String>> result = inverseMap(map);
        assertThat(result, IsMapWithSize.aMapWithSize(2));
        assertThat(result.get(1), containsInAnyOrder("a", "c"));
        assertThat(result.get(2), containsInAnyOrder("b"));
    }

    @Test
    void testStringHasChanged() {
        assertTrue(Utils.stringHasChanged("test1", "test2"));
        assertTrue(Utils.stringHasChanged("test1", null));
        assertTrue(Utils.stringHasChanged(null, "test2"));
        assertTrue(Utils.stringHasChanged("test1", ""));
        assertTrue(Utils.stringHasChanged("", "test2"));
        assertTrue(Utils.stringHasChanged("tEsT1", "test1"));

        assertFalse(Utils.stringHasChanged("", null));
        assertFalse(Utils.stringHasChanged("  ", null));
        assertFalse(Utils.stringHasChanged(null, ""));
        assertFalse(Utils.stringHasChanged(null, "  "));

        assertFalse(Utils.stringHasChanged("test", "test"));
        assertFalse(Utils.stringHasChanged(null, null));
        assertFalse(Utils.stringHasChanged("", ""));
    }

    @Test
    void GIVEN_a_runnable_WHEN_once_used_to_call_runnable_multiple_times_THEN_runnable_runs_only_once() {
        for (int i = 0; i < 5; i++) {
            once(mockRunnable);
        }
        verify(mockRunnable, times(1)).run();
    }
}
