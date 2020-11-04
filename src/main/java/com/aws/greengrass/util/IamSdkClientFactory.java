/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.deployment.DeviceConfiguration;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

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

    private static Map<String, Region> regionConverter = new HashMap<>();

    @Inject
    private static DeviceConfiguration deviceConfiguration;

    static {
        regionConverter.put("cn-northwest-1", Region.AWS_CN_GLOBAL);
        regionConverter.put("cn-north-1", Region.AWS_CN_GLOBAL);
        regionConverter.put("us-gov-east-1", Region.AWS_US_GOV_GLOBAL);
        regionConverter.put("us-gov-west-1", Region.AWS_US_GOV_GLOBAL);
        regionConverter.put("us-iso-east-1", Region.AWS_ISO_GLOBAL);
        regionConverter.put("us-isob-east-1", Region.AWS_ISO_B_GLOBAL);
    }

    private IamSdkClientFactory() {
    }

    /**
     * Build IamClient.
     *
     * @return IamClient instance
     */
    public static IamClient getIamClient() {
        String awsRegion = Coerce.toString(deviceConfiguration.getAWSRegion());
        Region region = regionConverter.getOrDefault(awsRegion, Region.AWS_GLOBAL);

        return IamClient.builder().region(region).httpClient(ProxyUtils.getSdkHttpClient())
                .overrideConfiguration(ClientOverrideConfiguration.builder().retryPolicy(retryPolicy).build()).build();
    }
}
