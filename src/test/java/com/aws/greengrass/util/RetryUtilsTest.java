/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryUtilsTest {

    Logger logger = LogManager.getLogger(this.getClass()).createChild();
    RetryUtils.RetryConfig config = RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofSeconds(1))
            .maxRetryInterval(Duration.ofSeconds(1)).maxAttempt(Integer.MAX_VALUE).retryableExceptions(
                    Arrays.asList(IOException.class)).build();

    @Test
    void GIVEN_retryableException_WHEN_runWithRetry_THEN_retry() throws Exception {
        AtomicInteger invoked = new AtomicInteger();
        RetryUtils.runWithRetry(config, () -> {
            if (invoked.getAndIncrement() < 1) {
                throw new IOException();
            }
            return invoked;
        }, "", logger);
        assertEquals(2, invoked.get());
    }

    @Test
    void GIVEN_nonRetryableException_WHEN_runWithRetry_THEN_throwException() {
        AtomicInteger invoked = new AtomicInteger();
        assertThrows(RuntimeException.class, () -> RetryUtils.runWithRetry(config, () -> {
            if (invoked.getAndIncrement() < 1) {
                throw new RuntimeException();
            }
            return invoked;
        }, "", logger));
        assertEquals(1, invoked.get());
    }
}
