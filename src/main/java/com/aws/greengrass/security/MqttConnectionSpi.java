/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.security;

import com.aws.greengrass.security.exceptions.MqttConnectionProviderException;
import com.aws.greengrass.security.exceptions.ServiceUnavailableException;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;

import java.net.URI;

public interface MqttConnectionSpi {

    AwsIotMqttConnectionBuilder getMqttConnectionBuilder(URI privateKeyUri, URI certificateUri)
            throws ServiceUnavailableException, MqttConnectionProviderException;

    String supportedKeyType();
}
