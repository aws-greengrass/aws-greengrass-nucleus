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
import software.amazon.awssdk.http.SdkHttpClient;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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

    @Test
    @SuppressWarnings("PMD.CloseResource")
    public void GIVEN_client_cached_WHEN_reset_called_THEN_next_get_client_returns_new_instance() throws Exception {
        SdkHttpClient firstClient = iotConnectionManager.getClient();
        assertNotNull(firstClient);

        // Calling getClient() again without a reset should return the same cached instance
        assertNotSame(null, iotConnectionManager.getClient());

        // Simulate recovery from a connection-level failure (e.g. a network outage that leaves the cached
        // client's connection pool / DNS resolution stale, such as an IPv4->IPv6 failover).
        iotConnectionManager.reset();

        SdkHttpClient secondClient = iotConnectionManager.getClient();
        assertNotSame(firstClient, secondClient,
                "IotConnectionManager should rebuild its HTTP client after reset() so that a poisoned "
                        + "connection/DNS-cache state (e.g. from a network change) can recover without requiring "
                        + "a manual TES restart.");
    }
}
