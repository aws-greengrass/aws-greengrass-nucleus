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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RetryUtilsTest {

    Logger logger = LogManager.getLogger(this.getClass()).createChild();
    RetryUtils.RetryConfig config = RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofSeconds(1))
            .maxRetryInterval(Duration.ofSeconds(1)).maxAttempt(Integer.MAX_VALUE).retryableExceptions(
                    Collections.singletonList(IOException.class)).build();

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

    @Test
    void GIVEN_differentiatedRetryConfig_WHEN_runWithRetry_THEN_retryDifferently() {
        AtomicInteger invoked = new AtomicInteger(0);
        List<RetryUtils.RetryConfig> configList = new ArrayList<>();

        configList.add(RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofSeconds(1))
                .maxRetryInterval(Duration.ofSeconds(1)).maxAttempt(3).retryableExceptions(
                        Collections.singletonList(IOException.class)).build());

        configList.add(RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofSeconds(1))
                .maxRetryInterval(Duration.ofSeconds(1)).maxAttempt(2).retryableExceptions(
                        Collections.singletonList(RuntimeException.class)).build());

        RetryUtils.DifferentiatedRetryConfig config = RetryUtils.DifferentiatedRetryConfig.builder()
                .retryConfigList(configList)
                .build();

        assertThrows(RuntimeException.class, () -> RetryUtils.runWithRetry(config, () -> {
            // throw IO exception on even number attempts -> 2 times
            // throw runtime exception on odd number attempts -> 1 times
            // at last it will throw runtime exception out because we only allow 2 max retries
            if (invoked.getAndIncrement() % 2 == 0) {
                throw new IOException();
            } else {
                throw new RuntimeException();
            }
        }, "", logger));
        assertEquals(4, invoked.get());
    }
}
