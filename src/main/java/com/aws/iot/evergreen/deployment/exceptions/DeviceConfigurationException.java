/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.exceptions;

public class DeviceConfigurationException extends Exception {
    public DeviceConfigurationException(String message, Throwable e) {
        super(message, e);
    }

    public DeviceConfigurationException(Throwable e) {
        super(e);
    }

    public DeviceConfigurationException(String message) {
        super(message);
    }
}
