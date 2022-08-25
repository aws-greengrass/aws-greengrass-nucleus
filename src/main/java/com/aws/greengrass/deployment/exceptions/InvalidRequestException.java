/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DEPLOYMENT_DOCUMENT_NOT_VALID;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class InvalidRequestException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidRequestException(String message, Throwable e) {
        super(message, e);
        super.getErrorCodes().add(DEPLOYMENT_DOCUMENT_NOT_VALID);
    }

    public InvalidRequestException(Throwable e) {
        super(e);
        super.getErrorCodes().add(DEPLOYMENT_DOCUMENT_NOT_VALID);
    }

    public InvalidRequestException(String message) {
        super(message);
        super.getErrorCodes().add(DEPLOYMENT_DOCUMENT_NOT_VALID);
    }

    public InvalidRequestException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.getErrorCodes().add(DEPLOYMENT_DOCUMENT_NOT_VALID);
        super.getErrorCodes().add(errorCode);
    }

    @Override
    public InvalidRequestException withErrorContext(String className, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(className, errorCode);
        return this;
    }
}
