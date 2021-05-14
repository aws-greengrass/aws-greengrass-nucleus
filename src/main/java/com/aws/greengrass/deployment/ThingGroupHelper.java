/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.RetryUtils;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceRequest;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceResponse;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

public class ThingGroupHelper {
    protected static final Logger logger = LogManager.getLogger(ComponentServiceHelper.class);
    private final GreengrassServiceClientFactory clientFactory;
    private final DeviceConfiguration deviceConfiguration;

    private final RetryUtils.RetryConfig clientExceptionRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1))
                    .maxRetryInterval(Duration.ofMinutes(1)).maxAttempt(Integer.MAX_VALUE)
                    .retryableExceptions(Arrays.asList(SdkClientException.class)).build();

    @Inject
    public ThingGroupHelper(GreengrassServiceClientFactory clientFactory, DeviceConfiguration deviceConfiguration) {
        this.clientFactory = clientFactory;
        this.deviceConfiguration = deviceConfiguration;
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

        Set<String> thingGroupNames = new HashSet<>();
        try {
            AtomicReference<String> nextToken = new AtomicReference<>();
            return RetryUtils.runWithRetry(clientExceptionRetryConfig,
                    () -> {
                        do {
                            ListThingGroupsForCoreDeviceRequest request = ListThingGroupsForCoreDeviceRequest.builder()
                                    .coreDeviceThingName(Coerce.toString(deviceConfiguration.getThingName()))
                                    .nextToken(nextToken.get()).build();
                            ListThingGroupsForCoreDeviceResponse response = clientFactory.getGreengrassV2DataClient()
                                    .listThingGroupsForCoreDevice(request);
                            response.thingGroups().forEach(thingGroup -> {
                                //adding direct thing group
                                thingGroupNames.add(thingGroup.thingGroupName());
                                //adding parent thing group
                                thingGroup.rootToParentThingGroups().forEach(parentThingGroup ->
                                        thingGroupNames.add(parentThingGroup.thingGroupName()));
                            });
                            nextToken.set(response.nextToken());
                        } while (nextToken.get() != null);

                        return thingGroupNames;
                    },
                    "get-thing-group-hierarchy", logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            logger.atError("Error").cause(e).log();
            throw new NonRetryableDeploymentTaskFailureException("Error fetching thing group information", e);
        }
    }
}
