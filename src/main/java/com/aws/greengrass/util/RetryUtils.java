/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.logging.api.LogEventBuilder;
import com.aws.greengrass.logging.api.Logger;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.List;

public class RetryUtils {

    // Need this to make spotbug check happy
    private RetryUtils() {
    }

    /**
     * Run a task with retry. Only exceptions in the retryable exception list are retried. Stop the retry when
     * interrupted.
     *
     * @param retryConfig     retry configuration
     * @param task            task to run
     * @param taskDescription task description
     * @param logger          logger
     * @param <T>             return type
     * @return return value
     * @throws Exception Exception
     */
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidCatchingGenericException",
            "PMD.AvoidInstanceofChecksInCatchClause"})
    public static <T> T runWithRetry(RetryConfig retryConfig, CrashableSupplier<T, Exception> task,
            String taskDescription, Logger logger) throws Exception {
        long retryInterval = retryConfig.getInitialRetryInterval().toMillis();
        int attempt = 1;
        Exception lastException = null;
        while (attempt <= retryConfig.maxAttempt) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(taskDescription + " task is interrupted");
            }
            try {
                return task.apply();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    throw e;
                }
                if (retryConfig.retryableExceptions.stream().anyMatch(c -> c.isInstance(e))) {
                    LogEventBuilder logBuild = logger.atDebug(taskDescription);
                    // Log first failed attempt at info so as not to spam logs
                    if (attempt == 1) {
                        logBuild = logger.atInfo(taskDescription);
                    }
                    logBuild.kv("task-attempt", attempt).setCause(e)
                            .log("task failed and will be retried");
                    lastException = e;
                    // TODO: [P45052340]: Add jitter to avoid clients retrying concurrently
                    Thread.sleep(retryInterval);
                    if (retryInterval < retryConfig.getMaxRetryInterval().toMillis()) {
                        retryInterval = retryInterval * 2;
                    } else {
                        retryInterval = retryConfig.getMaxRetryInterval().toMillis();
                    }
                    attempt++;
                } else {
                    throw e;
                }
            }
        }
        throw lastException;
    }

    @Builder
    @Getter
    public static class RetryConfig {
        @Builder.Default
        Duration initialRetryInterval = Duration.ofSeconds(1L);
        @Builder.Default
        Duration maxRetryInterval = Duration.ofMinutes(1L);
        @Builder.Default
        int maxAttempt = 10;
        List<Class> retryableExceptions;
    }
}
