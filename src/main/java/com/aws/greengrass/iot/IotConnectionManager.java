/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.iot;

import com.aws.greengrass.componentmanager.ClientConfigurationUtils;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.LockFactory;
import com.aws.greengrass.util.LockScope;
import com.aws.greengrass.util.Utils;
import software.amazon.awssdk.http.SdkHttpClient;

import java.io.Closeable;
import java.net.URI;
import java.util.concurrent.locks.Lock;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;

public class IotConnectionManager implements Closeable {
    private final DeviceConfiguration deviceConfiguration;
    private SdkHttpClient client;
    private final Lock lock = LockFactory.newReentrantLock(this);

    /**
     * Constructor.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     */
    @Inject
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public IotConnectionManager(final DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
        reconfigureOnConfigChange();
    }

    /**
     * Get URI for connecting to AWS IoT.
     * @return URI to AWS IoT, based on device configuration
     * @throws DeviceConfigurationException When device is not configured to get credentials
     */
    public URI getURI() throws DeviceConfigurationException {
        if (Utils.isEmpty(Coerce.toString(deviceConfiguration.getIotCredentialEndpoint()))) {
            throw new DeviceConfigurationException("Iot credential endpoint is not configured in Greengrass");
        }
        return URI.create("https://" + Coerce.toString(deviceConfiguration.getIotCredentialEndpoint()));
    }

    /**
     * Initializes and returns the SdkHttpClient.
     *
     */
    public SdkHttpClient getClient() {
        try (LockScope ls = LockScope.lock(lock)) {
            if (this.client == null) {
                this.client = initConnectionManager();
            }
            return this.client;
        }
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void reconfigureOnConfigChange() {
        deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null && (node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH)
                    || node.childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH))) {
                try (LockScope ls = LockScope.lock(lock)) {
                    if (this.client != null) {
                        this.client.close();
                        this.client = null;
                    }
                }
            }
        });
    }

    private SdkHttpClient initConnectionManager() {
        return ClientConfigurationUtils.getConfiguredClientBuilder(deviceConfiguration).build();
    }


    /**
     * Clean up underlying connections and close gracefully.
     */
    @Override
    public void close() {
        try (LockScope ls = LockScope.lock(lock)) {
            if (this.client != null) {
                this.client.close();
            }
        }
    }
}
