/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.iot;

import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.util.Coerce;
import software.amazon.awssdk.http.SdkHttpClient;

import java.io.Closeable;
import java.net.URI;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.ClientConfigurationUtils.getConfiguredClientBuilder;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_MQTT_NAMESPACE;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;

public class IotConnectionManager implements Closeable {
    private final DeviceConfiguration deviceConfiguration;
    private SdkHttpClient client;

    /**
     * Constructor.
     *
     * @param deviceConfiguration Device configuration helper getting cert and keys for mTLS
     */
    @Inject
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public IotConnectionManager(final DeviceConfiguration deviceConfiguration) {
        this.deviceConfiguration = deviceConfiguration;
        this.client = initConnectionManager();
        reconfigureOnConfigChange();
    }

    public URI getURI() {
        return URI.create("https://" + Coerce.toString(deviceConfiguration.getIotCredentialEndpoint()));
    }

    public synchronized SdkHttpClient getClient() {
        return this.client;
    }

    private void reconfigureOnConfigChange() {
        deviceConfiguration.onAnyChange((what, node) -> {
            if (WhatHappened.childChanged.equals(what) && node != null && (node.childOf(DEVICE_MQTT_NAMESPACE) || node
                    .childOf(DEVICE_PARAM_THING_NAME) || node.childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT) || node
                    .childOf(DEVICE_PARAM_PRIVATE_KEY_PATH) || node.childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node
                    .childOf(DEVICE_PARAM_ROOT_CA_PATH) || node.childOf(DEVICE_PARAM_AWS_REGION))) {
                synchronized (this) {
                    this.client.close();
                    this.client = initConnectionManager();
                }
            }
        });
    }

    private SdkHttpClient initConnectionManager() {
        return getConfiguredClientBuilder(deviceConfiguration).build();
    }


    /**
     * Clean up underlying connections and close gracefully.
     */
    @Override
    public synchronized void close() {
        if (this.client != null) {
            this.client.close();
        }
    }
}
