package com.aws.iot.evergreen.util;

import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.OrRetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnExceptionsCondition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.IamException;
import software.amazon.awssdk.services.iam.model.LimitExceededException;
import software.amazon.awssdk.services.iam.model.ServiceFailureException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Accessor for AWS IAM SDK.
 */
public final class IamSdkClientFactory {
    private static final Set<Class<? extends Exception>> retryableIamExceptions = new HashSet<>(
            Arrays.asList(IamException.class, LimitExceededException.class, ServiceFailureException.class));

    private static final RetryCondition retryCondition = OrRetryCondition
            .create(RetryCondition.defaultRetryCondition(), RetryOnExceptionsCondition.create(retryableIamExceptions));

    private static final RetryPolicy retryPolicy =
            RetryPolicy.builder().numRetries(5).backoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                    .retryCondition(retryCondition).build();

    private IamSdkClientFactory() {
    }

    /**
     * Build IamClient.
     *
     * @return IamClient instance
     */
    public static IamClient getIamClient() {
        // TODO : Add partition support
        return IamClient.builder().region(Region.AWS_GLOBAL)
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build()).build();
    }
}
