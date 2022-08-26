/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;


import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;

public class PackagingException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackagingException(String message) {
        super(message);
    }

    public PackagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public PackagingException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(errorCode);
    }

    public PackagingException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        super.addErrorCode(errorCode);
    }

    @Override
    public PackagingException withErrorContext(String className, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(className, errorCode);
        return this;
    }
}
