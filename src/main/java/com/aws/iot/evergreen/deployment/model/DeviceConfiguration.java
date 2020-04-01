/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DeviceConfiguration {
    private String thingName;
    private String certificateFilePath;
    private String privateKeyFilePath;
    private String rootCAFilePath;
    private String mqttClientEndpoint;
}
