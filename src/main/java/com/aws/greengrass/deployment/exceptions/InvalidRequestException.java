/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class InvalidRequestException extends Exception {

    static final long serialVersionUID = -3387516993124229948L;

    public InvalidRequestException(String message, Throwable e) {
        super(message, e);
    }

    public InvalidRequestException(Throwable e) {
        super(e);
    }

    public InvalidRequestException(String message) {
        super(message);
    }
}
