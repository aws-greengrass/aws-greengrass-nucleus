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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
     * @param differentiatedRetryConfig     differentiated retry config
     * @param task            task to run
     * @param taskDescription task description
     * @param logger          logger
     * @param <T>             return type
     * @return return value
     * @throws Exception Exception
     * @throws InterruptedException when the task thread is interrupted
     */
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidCatchingGenericException",
            "PMD.AvoidInstanceofChecksInCatchClause"})
    public static <T> T runWithRetry(DifferentiatedRetryConfig differentiatedRetryConfig,
                                     CrashableSupplier<T, Exception> task, String taskDescription, Logger logger)
            throws Exception {
        long retryInterval = 0;
        long totalAttempts = 0;
        long totalMaxAttempts = calculateTotalMaxAttempts(differentiatedRetryConfig);
        Map<RetryConfig, Integer> attemptMap = new HashMap<>();
        differentiatedRetryConfig.getRetryConfigList()
                .forEach(retryConfig -> attemptMap.put(retryConfig, 1));

        while (totalAttempts < totalMaxAttempts) {
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
                for (RetryConfig retryConfig : differentiatedRetryConfig.getRetryConfigList()) {
                    if (retryConfig.getRetryableExceptions().stream().anyMatch(c -> c.isInstance(e))) {
                        foundExceptionInMap = true;
                        // increment attempt count
                        int attempt = attemptMap.get(retryConfig);
                        if (attempt >= retryConfig.getMaxAttempt()) {
                            throw e;
                        }
                        attemptMap.put(retryConfig, attempt + 1);

                        // log the message
                        LogEventBuilder logBuilder = attempt == 1 || attempt % LOG_ON_FAILURE_COUNT == 0
                                ? logger.atInfo(taskDescription)
                                : logger.atDebug(taskDescription);
                        logBuilder.kv("task-attempt", attempt).setCause(e).log("task failed and will be retried");

                        // sleep with back-off
                        if (retryInterval == 0) {
                            retryInterval = retryConfig.getInitialRetryInterval().toMillis();
                        }
                        Thread.sleep(retryInterval / 2 + RANDOM.nextInt((int) (retryInterval / 2 + 1)));
                        retryInterval = Math.min(retryInterval * 2, retryConfig.getMaxRetryInterval().toMillis());

                        // break since exception is found
                        break;
                    }
                }
                if (!foundExceptionInMap) {
                    throw e;
                }
                totalAttempts++;
            }
        }
        return task.apply();
    }

    // Use long to avoid integer overflow
    private static long calculateTotalMaxAttempts(DifferentiatedRetryConfig config) {
        return config.getRetryConfigList().stream()
                .mapToLong(RetryConfig::getMaxAttempt)
                .sum();
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
        // keep compatibility with older versions
        List<Class> retryableExceptions;
    }

    @Builder(toBuilder = true)
    @Getter
    public static class DifferentiatedRetryConfig {
        // map between set of exception classes to retry on and max retry attempt for each set
        List<RetryConfig> retryConfigList;

        /**
         * Create a DifferentiatedRetryConfig from RetryConfig.
         * @param retryConfig retryConfig
         * @return differentiatedRetryConfig
         */
        public static DifferentiatedRetryConfig fromRetryConfig(RetryConfig retryConfig) {
            return DifferentiatedRetryConfig.builder()
                    .retryConfigList(Collections.singletonList(retryConfig))
                    .build();
        }

        // Used for unit test
        public void setInitialRetryIntervalForAll(Duration duration) {
            retryConfigList.forEach(retryConfig -> retryConfig.initialRetryInterval = duration);
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
