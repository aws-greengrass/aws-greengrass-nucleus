/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class DeviceConfigurationException extends Exception {

    static final long serialVersionUID = -3387516993124229948L;

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
