/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.exceptions.RetryableServerErrorException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.*;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.componentmanager.ComponentServiceHelper.CLIENT_RETRY_COUNT;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;

import static org.junit.jupiter.api.Assertions.assertThrows;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
class ThingGroupHelperTest {
    @Mock
    private DeviceConfiguration deviceConfiguration;

    @Mock
    private GreengrassV2DataClient client;

    @Mock
    private GreengrassServiceClientFactory clientFactory;

    @Mock
    private final AtomicReference<String> nextToken = new AtomicReference<>();

    private ThingGroupHelper helper;

    @Test
    void GIVEN_list_thing_groups_for_core_device_request_WHEN_500_error_then_retry(ExtensionContext context)
            throws Exception {
        ignoreExceptionOfType(context, RetryableServerErrorException.class);
        this.helper = spy(new ThingGroupHelper(clientFactory, deviceConfiguration));
        ListThingGroupsForCoreDeviceRequest request = ListThingGroupsForCoreDeviceRequest.builder()
                .coreDeviceThingName(Coerce.toString(deviceConfiguration.getThingName()))
                .nextToken(nextToken.get())
                .build();
        when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        when(clientFactory.fetchGreengrassV2DataClient()).thenReturn(client);

        when(client.listThingGroupsForCoreDevice(request))
                .thenThrow(GreengrassV2DataException.builder().statusCode(500).build());

        helper.setClientExceptionRetryConfig(
                helper.getClientExceptionRetryConfig().toBuilder().initialRetryInterval(Duration.ZERO).build());
        assertThrows(RetryableServerErrorException.class, () -> helper.listThingGroupsForDevice(3));

        verify(clientFactory, times(CLIENT_RETRY_COUNT)).fetchGreengrassV2DataClient();
    }
}
