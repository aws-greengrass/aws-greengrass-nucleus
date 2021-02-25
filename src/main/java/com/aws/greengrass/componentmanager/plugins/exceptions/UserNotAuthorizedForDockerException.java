/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.exceptions;

public class UserNotAuthorizedForDockerException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public UserNotAuthorizedForDockerException(String message) {
        super(message);
    }

    public UserNotAuthorizedForDockerException(String message, Throwable cause) {
        super(message, cause);
    }
}