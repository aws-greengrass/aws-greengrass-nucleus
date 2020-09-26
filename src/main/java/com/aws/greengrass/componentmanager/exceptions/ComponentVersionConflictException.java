/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

public class ComponentVersionConflictException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public ComponentVersionConflictException(String message) {
        super(message);
    }
}
