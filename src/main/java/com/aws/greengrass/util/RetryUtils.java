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
import java.util.Random;

public class RetryUtils {

    private static final Random RANDOM = new Random();
    private static final int LOG_ON_FAILURE_COUNT = 20;

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
        // if it's not the final attempt, execute and backoff on retryable exceptions
        while (attempt < retryConfig.maxAttempt) {
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
                    // Log first and every LOG_ON_FAILURE_COUNT failed attempt at info so as not to spam logs
                    // After the initial ramp up period , the task would be retried every 1 min and hence
                    // the failure will be logged once every 20 minutes.
                    if (attempt == 1 || attempt % LOG_ON_FAILURE_COUNT == 0) {
                        logBuild = logger.atInfo(taskDescription);
                    }
                    logBuild.kv("task-attempt", attempt).setCause(e)
                            .log("task failed and will be retried");
                    // Backoff with jitter strategy from EqualJitterBackoffStrategy in AWS SDK
                    Thread.sleep(retryInterval / 2 + RANDOM.nextInt((int) (retryInterval / 2 + 1)));
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
        // if it's the final attempt, return directly
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException(taskDescription + " task is interrupted");
        }
        return task.apply();
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

    /**
     * Check if given error code qualifies for triggering retry mechanism.
     *
     * @param errorCode     retry configuration
     * @return boolean
     */
    public static boolean retryErrorCodes(int errorCode) {
        return errorCode >= 500 || errorCode == 429;
    }
}
