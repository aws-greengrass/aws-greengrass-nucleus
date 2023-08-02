/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager.exceptions;

public class CustomPluginNotSupportedException extends ServiceLoadException {
    static final long serialVersionUID = -3387516993124229948L;

    public CustomPluginNotSupportedException(String message) {
        super(message);
    }

    public CustomPluginNotSupportedException(String message, Throwable cause) {
        super(message, cause);
    }

    public CustomPluginNotSupportedException(Throwable cause) {
        super(cause);
    }
}
