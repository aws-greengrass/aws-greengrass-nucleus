package com.aws.iot.evergreen.util;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.OrRetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnExceptionsCondition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.InternalException;
import software.amazon.awssdk.services.iot.model.InternalFailureException;
import software.amazon.awssdk.services.iot.model.LimitExceededException;
import software.amazon.awssdk.services.iot.model.ThrottlingException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Accessor for AWS IoT SDK.
 */
public final class IotSdkClientFactory {
    private static final Set<Class<? extends Exception>> retryableIoTExceptions = new HashSet<>(
            Arrays.asList(ThrottlingException.class, InternalException.class, InternalFailureException.class,
                    LimitExceededException.class));

    private static final RetryCondition retryCondition = OrRetryCondition
            .create(RetryCondition.defaultRetryCondition(), RetryOnExceptionsCondition.create(retryableIoTExceptions));

    private static final RetryPolicy retryPolicy =
            RetryPolicy.builder().numRetries(10).backoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                    .retryCondition(retryCondition).build();

    private IotSdkClientFactory() {
    }

    /**
     * Build IotClient for a desired region.
     *
     * @param awsRegion aws region
     * @return IotClient instance
     */
    public static IotClient getIotClient(String awsRegion) {
        return IotClient.builder().region(Region.of(awsRegion))
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build()).build();
    }

    /**
     * Build IotClient for desired region and credentials.
     *
     * @param awsRegion           aws region
     * @param credentialsProvider credentials provider
     * @return IotClient instance
     */
    public static IotClient getIotClient(Region awsRegion, AwsCredentialsProvider credentialsProvider) {
        return IotClient.builder().region(awsRegion).credentialsProvider(credentialsProvider)
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build()).build();
    }

    /**
     * Build IotClient for tests with custom retry logic.
     *
     * @param awsRegion                     aws region
     * @param additionalRetryableExceptions additional exceptions to retry on
     * @return IotClient instance
     */
    public static IotClient getIotClient(String awsRegion,
                                         Set<Class<? extends Exception>> additionalRetryableExceptions) {
        Set<Class<? extends Exception>> allExceptionsToRetryOn = new HashSet<>();
        allExceptionsToRetryOn.addAll(retryableIoTExceptions);
        allExceptionsToRetryOn.addAll(additionalRetryableExceptions);
        return IotClient.builder().region(Region.of(awsRegion)).overrideConfiguration(
                ClientOverrideConfiguration.builder().retryPolicy(
                        RetryPolicy.builder().numRetries(5).backoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                                .retryCondition(OrRetryCondition.create(RetryCondition.defaultRetryCondition(),
                                        RetryOnExceptionsCondition.create(allExceptionsToRetryOn))).build()).build())
                .build();
    }
}
