/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.util;

import static org.junit.Assert.*;
import org.junit.*;


public class LLTest {
    @Test public void T0() {
        assertEquals(45, LL.parseAngle("45"),1e-10);
        assertEquals(45.5, LL.parseAngle("45.5"),1e-10);
        assertEquals(45.5, LL.parseAngle("45 30"),1e-10);
        assertEquals(45.5, LL.parseAngle("45˚30'"),1e-10);
        assertEquals(45.5, LL.parseAngle("45˚30"),1e-10);
        assertEquals(45.5, LL.parseAngle("45˚30'n"),1e-10);
        assertEquals(-45.5, LL.parseAngle("45˚30's"),1e-10);
        assertEquals(45.5, LL.parseAngle("45˚30'E"),1e-10);
        assertEquals(-45.5, LL.parseAngle("45˚30'W"),1e-10);
    }
    
    @Test
    public void T1() {
        LL p1 = new LL("50 03 59N,005 42 53W");
        assertEquals("lat",50+3/60.+59/60./60.,p1.lat,1e-8);
        assertEquals("lon",-(5+42/60.+53/60./60.),p1.lon,1e-8);
        LL p2=new LL("58 38 38N,003 04 12W");
        assertEquals("distance",968.9e3,p1.distanceM(p2),2e2);
        assertEquals("distance",968.9e3,p2.distanceM(p1),2e2);
        assertEquals("bearingTo ", LL.parseAngle("009° 07′ 11″"), p1.bearingTo(p2),1e-3);
    }
    
}
