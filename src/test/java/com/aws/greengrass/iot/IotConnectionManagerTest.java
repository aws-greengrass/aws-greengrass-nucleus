/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.iot;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(GGExtension.class)
public class IotConnectionManagerTest {

    private DeviceConfiguration mockDeviceConfiguration;

    private IotConnectionManager iotConnectionManager;

    @BeforeEach
    public void setup() {
        mockDeviceConfiguration = mock(DeviceConfiguration.class);
        iotConnectionManager = new IotConnectionManager(mockDeviceConfiguration);
    }

    @Test
    public void GIVEN_cred_endpoint_not_set_WHEN_get_uri_THEN_throw_exception() {
        Topic mockTopic = mock(Topic.class);
        when(mockTopic.getOnce()).thenReturn("");
        when(mockDeviceConfiguration.getIotCredentialEndpoint()).thenReturn(mockTopic);
        assertThrows(DeviceConfigurationException.class, iotConnectionManager::getURI);
    }
}
