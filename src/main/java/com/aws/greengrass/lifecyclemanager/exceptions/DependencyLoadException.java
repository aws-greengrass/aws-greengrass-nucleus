/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager.exceptions;

/**
 * Exception when loading dependencies of Greengrass services.
 */
public class DependencyLoadException extends ServiceLoadException {
    static final long serialVersionUID = -3387516993124229948L;

    public DependencyLoadException(String message) {
        super(message);
    }

    public DependencyLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public DependencyLoadException(Throwable cause) {
        super(cause);
    }
}
