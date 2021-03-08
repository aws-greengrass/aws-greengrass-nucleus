/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import org.junit.jupiter.api.Test;
import vendored.com.google.common.util.concurrent.RateLimiter;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.CombinableMatcher.both;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.lessThanOrEqualTo;

class RateLimiterTest {
    @Test
    void GIVEN_ratelimiter_WHEN_queryForTimeToPermit_THEN_timeIsAccurate() throws InterruptedException {
        RateLimiter rl = RateLimiter.create(100);
        // Wait 1 second for the token bucket to be filled completely
        TimeUnit.SECONDS.sleep(1);

        for (int i=0; i < 200; i++) {
            long estimatedTimeMicros = rl.microTimeToNextPermit();
            long realTimeMicros = (long) (rl.acquire() * 1000.0 * 1000.0);

            // Check that the durationToNextPermit is within 500 microseconds of the real wait time
            assertThat(realTimeMicros,
                    is(both(greaterThan(estimatedTimeMicros - 500)).and(lessThanOrEqualTo(estimatedTimeMicros))));
        }
    }
}
