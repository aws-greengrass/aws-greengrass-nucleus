/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */
package com.aws.iot.evergreen.kernel;

import static com.aws.iot.evergreen.kernel.Periodicity.parseInterval;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
import org.junit.Test;

/**
 *
 * @author jag
 */
public class PeriodicityTest {
    
    @Test
    public void testSomeMethod() {
        assertEquals(1000,parseInterval("1 second"));
        assertEquals(TimeUnit.MINUTES.toMillis(3),parseInterval("  0x3 minutes "));
        assertEquals(TimeUnit.SECONDS.toMillis(77),parseInterval("77"));
        assertEquals(TimeUnit.DAYS.toMillis(21),parseInterval("  0x3 weeks "));
    }
    
}
