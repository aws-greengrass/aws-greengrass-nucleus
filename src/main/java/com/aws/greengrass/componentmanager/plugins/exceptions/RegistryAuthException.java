/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.componentmanager.plugins.exceptions;

import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;

public class RegistryAuthException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public RegistryAuthException(String message) {
        super(message);
    }

    public RegistryAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}