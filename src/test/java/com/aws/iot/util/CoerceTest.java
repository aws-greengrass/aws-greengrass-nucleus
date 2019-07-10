/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.util;

import static com.aws.iot.util.Coerce.*;
import org.junit.*;
import static org.junit.Assert.*;

public class CoerceTest {
    
    @Test
    public void T1() {
        assertEquals(true,toBoolean(true));
        assertEquals(true,toBoolean("true"));
        assertEquals(true,toBoolean(1));
        assertEquals(true,toBoolean("yes"));
        assertEquals(true,toBoolean(99));
        assertEquals(false,toBoolean(false));
        assertEquals(false,toBoolean("false"));
        assertEquals(false,toBoolean("bozo"));
        assertEquals(false,toBoolean(null));
        assertEquals(false,toBoolean(0));
    }
    @Test
    public void T2() {
        assertEquals(42,toInt(42));
        assertEquals(42,toInt(42.0));
        assertEquals(42,toInt(42.1));
        assertEquals(42,toInt("42"));
        assertEquals(42.0,toDouble(42),1e-10);
        assertEquals(3.14159,toDouble(3.14159),1e-10);
        assertEquals(3.14159,toDouble("3.14159"),1e-10);
    }
    @Test
    public void T3() {
        assertEquals("\"xx\"",toQuotedString("xx"));
        assertEquals("\"x\\nx\"",toQuotedString("x\nx"));
        assertEquals("\"x\\nx\\u0022\"",toQuotedString("x\nx\""));
    }
    
}
