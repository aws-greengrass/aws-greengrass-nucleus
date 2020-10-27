/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

public class NoAvailableComponentVersionException extends PackagingException {

    static final long serialVersionUID = -3387516993124229948L;

    public NoAvailableComponentVersionException(String message) {
        super(message);
    }

    public NoAvailableComponentVersionException(String message, Throwable cause) {
        super(message, cause);
    }
}
