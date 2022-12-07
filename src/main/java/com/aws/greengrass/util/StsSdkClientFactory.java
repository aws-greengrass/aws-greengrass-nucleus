/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.OrRetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnExceptionsCondition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.model.LimitExceededException;
import software.amazon.awssdk.services.iam.model.ServiceFailureException;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.StsException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Accessor for AWS STS SDK.
 */
public final class StsSdkClientFactory {
    private static final Set<Class<? extends Exception>> retryableIamExceptions = new HashSet<>(
            Arrays.asList(StsException.class, LimitExceededException.class, ServiceFailureException.class));

    private static final RetryCondition retryCondition = OrRetryCondition
            .create(RetryCondition.defaultRetryCondition(), RetryOnExceptionsCondition.create(retryableIamExceptions));

    private static final RetryPolicy retryPolicy =
            RetryPolicy.builder().numRetries(5).backoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                    .retryCondition(retryCondition).build();

    private StsSdkClientFactory() {
    }

    /**
     * Build StsClient.
     *
     * @param awsRegion aws region
     * @return StsClient instance
     */
    public static StsClient getStsClient(String awsRegion) {
        return StsClient.builder().region(Region.of(awsRegion))
                .httpClientBuilder(ProxyUtils.getSdkHttpClientBuilder())
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build()).build();
    }
}
