/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.RetryUtils;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceRequest;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceResponse;

import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

public class ThingGroupHelper {
    protected static final Logger logger = LogManager.getLogger(ThingGroupHelper.class);
    public static final String THING_GROUP_RESOURCE_TYPE = "thinggroup";
    public static final String THING_GROUP_RESOURCE_TYPE_PREFIX = THING_GROUP_RESOURCE_TYPE + "/";
    private static final int DEFAULT_RETRY_COUNT = Integer.MAX_VALUE;
    static final List<Class> DEVICE_OFFLINE_INDICATIVE_EXCEPTIONS = Arrays.asList(SdkClientException.class,
            DeviceConfigurationException.class);
    private final GreengrassServiceClientFactory clientFactory;
    private final DeviceConfiguration deviceConfiguration;

    @Inject
    public ThingGroupHelper(GreengrassServiceClientFactory clientFactory, DeviceConfiguration deviceConfiguration) {
        this.clientFactory = clientFactory;
        this.deviceConfiguration = deviceConfiguration;
    }

    /**
     * Retrieve the thing group names the device belongs to.
     *
     * @return list of thing group names
     * @throws Exception when not able to fetch thing group names
     */
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidRethrowingException"})
    public Optional<Set<String>> listThingGroupsForDevice() throws Exception {
        return listThingGroupsForDevice(DEFAULT_RETRY_COUNT);
    }

    /**
     * Retrieve the thing group names the device belongs to.
     *
     * @param retryCount desired retry count
     * @return list of thing group names
     * @throws Exception when not able to fetch thing group names
     */
    @SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidRethrowingException"})
    public Optional<Set<String>> listThingGroupsForDevice(int retryCount) throws Exception {

        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            return Optional.empty();
        }
        AtomicReference<String> nextToken = new AtomicReference<>();
        Set<String> thingGroupNames = new HashSet<>();

        RetryUtils.RetryConfig clientExceptionRetryConfig =
                RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1))
                        .maxRetryInterval(Duration.ofMinutes(1)).maxAttempt(retryCount)
                        .retryableExceptions(DEVICE_OFFLINE_INDICATIVE_EXCEPTIONS).build();

        return RetryUtils.runWithRetry(clientExceptionRetryConfig, () -> {
            do {
                GreengrassV2DataClient client = clientFactory.getGreengrassV2DataClient();
                if (client == null) {
                    String errorMessage = clientFactory.getConfigValidationError().orElse("Could not get "
                            + "GreengrassV2DataClient");
                    throw new DeviceConfigurationException(errorMessage);
                }

                ListThingGroupsForCoreDeviceRequest request = ListThingGroupsForCoreDeviceRequest.builder()
                        .coreDeviceThingName(Coerce.toString(deviceConfiguration.getThingName()))
                        .nextToken(nextToken.get()).build();

                ListThingGroupsForCoreDeviceResponse response = client.listThingGroupsForCoreDevice(request);
                response.thingGroups().forEach(thingGroup -> {
                    //adding direct thing groups
                    thingGroupNames.add(THING_GROUP_RESOURCE_TYPE_PREFIX + thingGroup.thingGroupName());
                    //adding parent thing groups
                    thingGroup.rootToParentThingGroups().forEach(parentGroup -> thingGroupNames
                            .add(THING_GROUP_RESOURCE_TYPE_PREFIX + parentGroup.thingGroupName()));
                });
                nextToken.set(response.nextToken());
            } while (nextToken.get() != null);

            return Optional.of(thingGroupNames);
        }, "get-thing-group-hierarchy", logger);
    }
}
