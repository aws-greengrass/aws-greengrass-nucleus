/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;


import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;

import java.util.List;

public class PackagingException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackagingException(String message) {
        super(message);
    }

    public PackagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public PackagingException(String message, DeploymentErrorCode errorCode) {
        super(message, errorCode);
    }

    public PackagingException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause, errorCode);
    }

    public PackagingException(String message, List<DeploymentErrorCode> errorCodes) {
        super(message, errorCodes);
    }

    public PackagingException(String message, Throwable cause, List<DeploymentErrorCode> errorCodes) {
        super(message, cause, errorCodes);
    }

    @Override
    public PackagingException withErrorContext(Class<? extends Throwable> clazz, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(clazz, errorCode);
        return this;
    }
}
