/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.lifecyclemanager.Periodicity.parseInterval;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author jag
 */
@ExtendWith(GGExtension.class)
class PeriodicityTest {

    @Test
    void testSomeMethod() {
        assertEquals(1000, parseInterval("1 second"));
        assertEquals(TimeUnit.MINUTES.toMillis(3), parseInterval("  0x3 minutes "));
        assertEquals(TimeUnit.SECONDS.toMillis(77), parseInterval("77"));
        assertEquals(TimeUnit.DAYS.toMillis(21), parseInterval("  0x3 weeks "));
    }

}
