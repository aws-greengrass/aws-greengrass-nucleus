/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class ConnectionUnavailableException extends Exception {

    static final long serialVersionUID = -3387516993124229948L;

    public ConnectionUnavailableException(String message, Throwable e) {
        super(message, e);
    }

    public ConnectionUnavailableException(Throwable e) {
        super(e);
    }

    public ConnectionUnavailableException(String message) {
        super(message);
    }
}
