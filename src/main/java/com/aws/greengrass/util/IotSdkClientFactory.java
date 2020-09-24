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
        IotClientBuilder iotClientBuilder = IotClient.builder().region(Region.of(awsRegion))
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build());
        if (stage != EnvironmentStage.PROD) {
            URI endpoint = new URI(String.format(IOT_CONTROL_PLANE_ENDPOINT_FORMAT, stage.value, awsRegion));
            iotClientBuilder.endpointOverride(endpoint);
        }
        return iotClientBuilder.build();
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
     * Build IotClient for desired region, stage and credentials.
     *
     * @param awsRegion           aws region
     * @param stage               {@link EnvironmentStage}
     * @param credentialsProvider credentials provider
     * @return
     */
    public static IotClient getIotClient(String awsRegion, EnvironmentStage stage,
                                         AwsCredentialsProvider credentialsProvider) throws URISyntaxException {
        IotClientBuilder iotClientBuilder = IotClient.builder().region(Region.of(awsRegion))
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build());
        if (stage != EnvironmentStage.PROD) {
            URI endpoint = new URI(String.format(IOT_CONTROL_PLANE_ENDPOINT_FORMAT, stage.value, awsRegion));
            iotClientBuilder.endpointOverride(endpoint);
        }
        return iotClientBuilder.build();
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
        Set<Class<? extends Exception>> allExceptionsToRetryOn = new HashSet<>();
        allExceptionsToRetryOn.addAll(retryableIoTExceptions);
        allExceptionsToRetryOn.addAll(additionalRetryableExceptions);
        IotClientBuilder iotClientBuilder = IotClient.builder().region(Region.of(awsRegion)).overrideConfiguration(
                ClientOverrideConfiguration.builder().retryPolicy(
                        RetryPolicy.builder().numRetries(5).backoffStrategy(BackoffStrategy.defaultThrottlingStrategy())
                                .retryCondition(OrRetryCondition.create(RetryCondition.defaultRetryCondition(),
                                        RetryOnExceptionsCondition.create(allExceptionsToRetryOn))).build()).build());
        if (stage != EnvironmentStage.PROD) {
            String endpoint = String.format(IOT_CONTROL_PLANE_ENDPOINT_FORMAT, stage.value,
                    awsRegion);
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
            // TODO: throw exception
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
