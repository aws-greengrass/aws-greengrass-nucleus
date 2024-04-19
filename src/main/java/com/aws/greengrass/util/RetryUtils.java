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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class RetryUtils {

    public static final Random RANDOM = new Random();
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
        return runWithRetry(DifferentiatedRetryConfig.fromRetryConfig(retryConfig), task, taskDescription, logger);
    }

    /**
     * Run a task with differentiated retry behaviors. Different maximum retry attempts based on different exception
     * types. Stop the retry when interrupted.
     * @param retryConfig     differentiated retry config
     * @param task            task to run
     * @param taskDescription task description
     * @param logger          logger
     * @param <T>             return type
     * @return return value
     * @throws Exception Exception
     */
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidCatchingGenericException",
            "PMD.AvoidInstanceofChecksInCatchClause"})
    public static <T> T runWithRetry(DifferentiatedRetryConfig retryConfig, CrashableSupplier<T, Exception> task,
                                     String taskDescription, Logger logger) throws Exception {
        long retryInterval = retryConfig.getInitialRetryInterval().toMillis();
        // key = set of retryable exceptions
        // value = current number of attempt counts
        Map<Set<Class>, Integer> attemptMap = new HashMap<>();
        retryConfig.getRetryMap().keySet().forEach(exceptionSet -> attemptMap.put(exceptionSet, 1));

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException(taskDescription + " task is interrupted");
            }

            try {
                return task.apply();
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    throw e;
                }

                boolean foundExceptionInMap = false;
                for (Map.Entry<Set<Class>, Integer> entry : retryConfig.retryMap.entrySet()) {
                    // if any set matches the exception type, either increment the attempt count or throw the exception
                    if (entry.getKey().stream().anyMatch(c -> c.isInstance(e))) {
                        foundExceptionInMap = true;
                        int maxAttempt = entry.getValue();
                        int attempt = attemptMap.get(entry.getKey());

                        if (attempt < maxAttempt) {
                            LogEventBuilder logBuild = logger.atDebug(taskDescription);
                            if (attempt == 1 || attempt % LOG_ON_FAILURE_COUNT == 0) {
                                logBuild = logger.atInfo(taskDescription);
                            }
                            logBuild.kv("task-attempt", attempt).setCause(e)
                                    .log("task failed and will be retried");
                            // sleep with back-off
                            Thread.sleep(retryInterval / 2 + RANDOM.nextInt((int) (retryInterval / 2 + 1)));
                            if (retryInterval < retryConfig.getMaxRetryInterval().toMillis()) {
                                retryInterval = retryInterval * 2;
                            } else {
                                retryInterval = retryConfig.getMaxRetryInterval().toMillis();
                            }
                            attemptMap.put(entry.getKey(), attempt + 1);
                        } else {
                            // exceeding max attempt
                            throw e;
                        }
                    }
                }
                // if exception type not found in retryMap, then throw
                if (!foundExceptionInMap) {
                    throw e;
                }
            }
        }
    }

    @Builder(toBuilder = true)
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

    @Builder(toBuilder = true)
    @Getter
    public static class DifferentiatedRetryConfig {
        @Builder.Default
        Duration initialRetryInterval = Duration.ofSeconds(1L);
        @Builder.Default
        Duration maxRetryInterval = Duration.ofMinutes(1L);
        // map between set of exception classes to retry on and max retry attempt for each set
        Map<Set<Class>, Integer> retryMap;

        /**
         * Create a DifferentiatedRetryConfig from RetryConfig.
         * @param retryConfig retryConfig
         * @return differentiatedRetryConfig
         */
        public static DifferentiatedRetryConfig fromRetryConfig(RetryConfig retryConfig) {
            Map<Set<Class>, Integer> retryMap = new HashMap<>();
            retryMap.put(new HashSet<>(retryConfig.retryableExceptions), retryConfig.maxAttempt);
            return DifferentiatedRetryConfig.builder()
                    .initialRetryInterval(retryConfig.getInitialRetryInterval())
                    .maxRetryInterval(retryConfig.getMaxRetryInterval())
                    .retryMap(retryMap)
                    .build();
        }
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
