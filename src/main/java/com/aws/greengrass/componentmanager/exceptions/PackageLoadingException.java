/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_PACKAGE_LOADING_ERROR;

// TODO: [P41216693]: Think about refactoring this to PackageIOException
@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class PackageLoadingException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageLoadingException(String message) {
        super(message);
        super.addErrorCode(COMPONENT_PACKAGE_LOADING_ERROR);
    }

    public PackageLoadingException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(COMPONENT_PACKAGE_LOADING_ERROR);
    }

    public PackageLoadingException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(COMPONENT_PACKAGE_LOADING_ERROR);
        super.addErrorCode(errorCode);

    }

    public PackageLoadingException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        super.addErrorCode(COMPONENT_PACKAGE_LOADING_ERROR);
        super.addErrorCode(errorCode);
    }

    @Override
    public PackageLoadingException withErrorContext(String className, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(className, errorCode);
        return this;
    }
}
