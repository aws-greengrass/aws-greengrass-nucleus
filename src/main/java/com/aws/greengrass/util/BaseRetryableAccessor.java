/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

/**
 * Base accessor with custom logic to retry with backoff on configurable list of exceptions.
 */
public class BaseRetryableAccessor {

    /**
     * Execute with retries.
     *
     * @param tries                no of retries
     * @param initialBackoffMillis backoff in milliseconds
     * @param func                 executable action
     * @param retryableExceptions  exceptions to retry on
     * @param <T>                  response
     * @param <E>                  exception
     * @return response/exception
     * @throws E exception while talking via AWS SDK
     */
    @SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingThrowable"})
    public <T, E extends Throwable> T retry(int tries, int initialBackoffMillis, CrashableSupplier<T, E> func,
                                            Iterable<Class<? extends Throwable>> retryableExceptions) throws E {
        E lastException = null;
        int tryCount = 0;
        while (tryCount++ < tries) {
            try {
                return func.apply();
            } catch (Throwable e) {
                boolean retryable = false;
                lastException = (E) e;

                for (Class<?> t : retryableExceptions) {
                    if (t.isAssignableFrom(e.getClass())) {
                        retryable = true;
                        break;
                    }
                }

                // If not retryable, immediately throw it
                if (!retryable) {
                    throw lastException;
                }

                // Sleep with backoff
                try {
                    Thread.sleep((long) initialBackoffMillis * tryCount);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        throw lastException;
    }
}
