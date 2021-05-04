/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.RetryUtils;
import software.amazon.awssdk.core.exception.SdkClientException;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.inject.Inject;

public class ThingGroupHelper {
    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);

    private final GreengrassServiceClientFactory clientFactory;

    private final RetryUtils.RetryConfig clientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1))
                    .maxRetryInterval(Duration.ofMinutes(1)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class)).build();

    @Inject
    public ThingGroupHelper(GreengrassServiceClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Retrieve the thing group names the device belongs to.
     * @return list of thing group names
     * @throws InterruptedException if the thread is interrupted when fetching thing group list
     * @throws NonRetryableDeploymentTaskFailureException  when not able to fetch thing group names
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    public Set<String> listThingGroupsForDevice()
            throws InterruptedException, NonRetryableDeploymentTaskFailureException {
        //TODO: implement method when cloud api is available
        try {
            RetryUtils.runWithRetry(clientExceptionRetryConfig,
                    () -> {
                        return clientFactory.getGreengrassV2DataClient();
                    },
                    "get-thing-group-hierarchy", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            logger.atError("Error").log();
            throw new NonRetryableDeploymentTaskFailureException("Error fetching thing group information", e);
        }

        return new HashSet<>();
    }
}
