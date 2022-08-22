/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import java.util.Arrays;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_PACKAGE_LOADING_ERROR;

// TODO: [P41216693]: Think about refactoring this to PackageIOException
public class PackageLoadingException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageLoadingException(String message) {
        super(message, COMPONENT_PACKAGE_LOADING_ERROR);
    }

    public PackageLoadingException(String message, Throwable cause) {
        super(message, cause, COMPONENT_PACKAGE_LOADING_ERROR);
    }

    public PackageLoadingException(String message, DeploymentErrorCode errorCode) {
        super(message, Arrays.asList(COMPONENT_PACKAGE_LOADING_ERROR, errorCode));
    }

    public PackageLoadingException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause, Arrays.asList(COMPONENT_PACKAGE_LOADING_ERROR, errorCode));
    }

    @Override
    public PackageLoadingException withErrorContext(Class<? extends Throwable> clazz, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(clazz, errorCode);
        return this;
    }
}
