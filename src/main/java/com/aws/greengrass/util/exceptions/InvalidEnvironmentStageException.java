/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.exceptions;

public class InvalidEnvironmentStageException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidEnvironmentStageException(String message) {
        super(message);
    }
}
