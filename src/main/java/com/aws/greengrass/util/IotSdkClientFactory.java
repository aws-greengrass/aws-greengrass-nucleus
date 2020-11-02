/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.util.exceptions.InvalidEnvironmentStageException;
import lombok.AllArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.core.retry.backoff.BackoffStrategy;
import software.amazon.awssdk.core.retry.conditions.OrRetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryCondition;
import software.amazon.awssdk.core.retry.conditions.RetryOnExceptionsCondition;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.IotClientBuilder;
import software.amazon.awssdk.services.iot.model.InternalException;
import software.amazon.awssdk.services.iot.model.InternalFailureException;
import software.amazon.awssdk.services.iot.model.LimitExceededException;
import software.amazon.awssdk.services.iot.model.ThrottlingException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Accessor for AWS IoT SDK.
 */
public final class IotSdkClientFactory {
    private static final Set<Class<? extends Exception>> retryableIoTExceptions = new HashSet<>(
            Arrays.asList(ThrottlingException.class, InternalException.class, InternalFailureException.class,
                    LimitExceededException.class));

    private static final String IOT_CONTROL_PLANE_ENDPOINT_FORMAT = "https://%s.%s.iot.amazonaws.com";

    private IotSdkClientFactory() {
    }

    /**
     * Build IotClient for a desired region.
     *
     * @param awsRegion aws region
     * @param stage {@link EnvironmentStage}
     * @return IotClient instance
     * @throws URISyntaxException when Iot endpoint is malformed
     */
    public static IotClient getIotClient(String awsRegion, EnvironmentStage stage) throws URISyntaxException {
        return getIotClient(Region.of(awsRegion), stage, null, Collections.emptySet());
    }

    /**
     * Build IotClient for desired region and credentials.
     *
     * @param awsRegion           aws region
     * @param credentialsProvider credentials provider
     * @return IotClient instance
     * @throws URISyntaxException when Iot endpoint is malformed
     */
    public static IotClient getIotClient(Region awsRegion, AwsCredentialsProvider credentialsProvider)
            throws URISyntaxException {
        return getIotClient(awsRegion, EnvironmentStage.PROD, credentialsProvider, Collections.emptySet());
    }

    /**
     * Build IotClient for desired region, stage and credentials.
     *
     * @param awsRegion           aws region
     * @param stage               {@link EnvironmentStage}
     * @param credentialsProvider credentials provider
     * @return IotClient instance
     * @throws URISyntaxException when Iot endpoint is malformed
     */
    public static IotClient getIotClient(Region awsRegion, EnvironmentStage stage,
                                         AwsCredentialsProvider credentialsProvider) throws URISyntaxException {
        return getIotClient(awsRegion, stage, credentialsProvider, Collections.emptySet());
    }

    /**
     * Build IotClient for tests with custom retry logic.
     *
     * @param awsRegion                     aws region
     * @param additionalRetryableExceptions additional exceptions to retry on
     * @param stage {@link EnvironmentStage}
     * @return IotClient instance
     * @throws URISyntaxException when Iot endpoint is malformed
     */
    public static IotClient getIotClient(String awsRegion, EnvironmentStage stage,
                                         Set<Class<? extends Exception>> additionalRetryableExceptions)
            throws URISyntaxException {
        return getIotClient(Region.of(awsRegion), stage, null, additionalRetryableExceptions);
    }

    /**
     * Build IotClient for desired region, stage and credentials with custom retry logic.
     * @param awsRegion                     aws region
     * @param stage                         {@link EnvironmentStage}
     * @param credentialsProvider           credentials provider
     * @param additionalRetryableExceptions additional exceptions to retry on
     * @return IotClient instance
     * @throws URISyntaxException when Iot endpoint is malformed
     */
    public static IotClient getIotClient(Region awsRegion, EnvironmentStage stage,
                                         AwsCredentialsProvider credentialsProvider,
                                         Set<Class<? extends Exception>> additionalRetryableExceptions)
            throws URISyntaxException {
        Set<Class<? extends Exception>> allExceptionsToRetryOn = new HashSet<>();
        allExceptionsToRetryOn.addAll(retryableIoTExceptions);
        allExceptionsToRetryOn.addAll(additionalRetryableExceptions);

        int numRetries = 5;
        if (additionalRetryableExceptions.isEmpty()) {
            numRetries = 10;
        }

        RetryCondition retryCondition = OrRetryCondition.create(RetryCondition.defaultRetryCondition(),
                RetryOnExceptionsCondition.create(allExceptionsToRetryOn));
        RetryPolicy retryPolicy = RetryPolicy.builder().numRetries(numRetries)
                .backoffStrategy(BackoffStrategy.defaultThrottlingStrategy()).retryCondition(retryCondition).build();
        IotClientBuilder iotClientBuilder =
                IotClient.builder().region(awsRegion).httpClient(ProxyUtils.getSdkHttpClient()).overrideConfiguration(
                ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build());

        if (credentialsProvider != null) {
            iotClientBuilder.credentialsProvider(credentialsProvider);
        }

        if (stage != EnvironmentStage.PROD) {
            String endpoint = String.format(IOT_CONTROL_PLANE_ENDPOINT_FORMAT, stage.value, awsRegion);
            iotClientBuilder.endpointOverride(new URI(endpoint));
        }

        return iotClientBuilder.build();
    }

    @AllArgsConstructor
    public enum EnvironmentStage {
        PROD("prod"),
        GAMMA("gamma"),
        BETA("beta");

        String value;

        /**
         * Convert string to {@link EnvironmentStage}.
         * @param stage The string representation of the environment stage
         * @return {@link EnvironmentStage}
         * @throws InvalidEnvironmentStageException when the given stage is invalid
         */
        public static EnvironmentStage fromString(String stage) throws InvalidEnvironmentStageException {
            for (EnvironmentStage validStage : values()) {
                if (stage.equalsIgnoreCase(validStage.value)) {
                    return validStage;
                }
            }
            String errorMessage = String.format("%s is not a valid environment stage. Valid stages are %s", stage,
                    Arrays.toString(values()));
            throw new InvalidEnvironmentStageException(errorMessage);
        }

        @Override
        public String toString() {
            return value;
        }
    }
}
