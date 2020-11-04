/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

// TODO: [P41216693]: Think about refactoring this to PackageIOException
public class PackageLoadingException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageLoadingException(String message) {
        super(message);
    }

    public PackageLoadingException(String message, Throwable cause) {
        super(message, cause);
    }
}
