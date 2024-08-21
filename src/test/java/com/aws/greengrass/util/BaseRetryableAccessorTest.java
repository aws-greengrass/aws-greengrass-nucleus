/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.apache.http.HttpException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class BaseRetryableAccessorTest {
    private static final String MOCK_RESPONSE = "MockResponse";
    private static final int RETRY_COUNT = 3;
    private static final int BACKOFF_MILLIS = 100;
    private static final Iterable<Class<? extends Throwable>> RETRYABLE_EXCEPTIONS = new HashSet<>(
            Arrays.asList(HttpException.class));
    @Mock
    private CrashableSupplier func;

    BaseRetryableAccessor accessor = new BaseRetryableAccessor();

    @Test
    void Given_retryable_accessor_WHEN_no_error_THEN_success () throws Throwable {
        when(func.apply()).thenReturn(MOCK_RESPONSE);
        String response = (String) accessor.retry(RETRY_COUNT, BACKOFF_MILLIS, func, RETRYABLE_EXCEPTIONS);
        assertEquals(MOCK_RESPONSE, response);
        verify(func, times(1)).apply();
    }

    @Test
    void Given_retryable_accessor_WHEN_retryabale_error_THEN_success () throws Throwable {
        when(func.apply()).thenThrow(HttpException.class).thenThrow(HttpException.class).thenReturn(MOCK_RESPONSE);
        String response = (String) accessor.retry(RETRY_COUNT, BACKOFF_MILLIS, func, RETRYABLE_EXCEPTIONS);
        assertEquals(MOCK_RESPONSE, response);
        verify(func, times(3)).apply();
    }

    @Test
    void Given_retryable_accessor_WHEN_retryabale_error_retry_count_exhausts_THEN_fail () throws Throwable {
        when(func.apply()).thenThrow(HttpException.class).thenThrow(HttpException.class).thenThrow(HttpException.class);
        assertThrows(HttpException.class, () -> accessor.retry(RETRY_COUNT, BACKOFF_MILLIS, func,
                RETRYABLE_EXCEPTIONS));
        verify(func, times(3)).apply();
    }

    @Test
    void Given_retryable_accessor_WHEN_non_retryabale_error_THEN_fail () throws Throwable {
        when(func.apply()).thenThrow(RuntimeException.class);
        assertThrows(RuntimeException.class, () -> accessor.retry(RETRY_COUNT, BACKOFF_MILLIS, func,
                RETRYABLE_EXCEPTIONS));
        verify(func, times(1)).apply();
    }
}
