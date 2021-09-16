/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.security.exceptions;

public class KeyLoadingException extends Exception {

    static final long serialVersionUID = -3387516993124229948L;

    public KeyLoadingException(String message) {
        super(message);
    }

    public KeyLoadingException(String message, Throwable e) {
        super(message, e);
    }
}
