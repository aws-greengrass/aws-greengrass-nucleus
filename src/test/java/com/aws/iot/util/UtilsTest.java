/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.util;

import static com.aws.iot.util.Utils.*;
import java.nio.*;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class UtilsTest {
    
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
    private static void testLparse1(String s, long expecting, String remaining) {
        CharBuffer cb = CharBuffer.wrap(s);
        long v = Utils.parseLong(cb);
        assertEquals(s, expecting, v);
        assertEquals(s, remaining, cb.toString());
    }
    @Test
    public void T2() {
        Map m = new LinkedHashMap();
        m.put("CDC","6400");
        m.put("PDP","8/I");
        Object[] o = { 5, "hello", new HashMap(), m };
        assertEquals("[5,\"hello\",{},{CDC:\"6400\",PDP:\"8/I\"}]", deepToString(o,80).toString());
        assertEquals("[5,\"hello\"...]", deepToString(o,6).toString());
        assertEquals("[5,\"hello\",{},{CDC:\"6400\"...}]", deepToString(o,20).toString());
    }
    @Test
    public void T3() {
        assertEquals("foo",dequote("foo"));
        assertEquals("foo\nbar",dequote("\"foo\\nbar\""));
        assertEquals("foo\nbar",dequote("\"foo\\u000Abar\""));
    }
    
}
