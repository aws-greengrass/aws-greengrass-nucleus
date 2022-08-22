/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import java.util.Arrays;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DEPLOYMENT_DOCUMENT_NOT_VALID;

public class InvalidRequestException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidRequestException(String message, Throwable e) {
        super(message, e);
    }

    public InvalidRequestException(Throwable e) {
        super(e);
    }

    public InvalidRequestException(String message) {
        super(message);
    }

    public InvalidRequestException(String message, DeploymentErrorCode errorCode) {
        super(message, Arrays.asList(DEPLOYMENT_DOCUMENT_NOT_VALID, errorCode));
    }

    @Override
    public InvalidRequestException withErrorContext(Class<? extends Throwable> clazz, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(clazz, errorCode);
        return this;
    }
}
