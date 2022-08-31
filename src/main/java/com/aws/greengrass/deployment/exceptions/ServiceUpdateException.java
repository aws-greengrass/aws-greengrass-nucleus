/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorType;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_UPDATE_ERROR;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class ServiceUpdateException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public ServiceUpdateException(String message) {
        super(message);
        super.addErrorCode(COMPONENT_UPDATE_ERROR);
    }

    public ServiceUpdateException(Throwable e) {
        super(e);
        super.addErrorCode(COMPONENT_UPDATE_ERROR);

    }

    public ServiceUpdateException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(COMPONENT_UPDATE_ERROR);
        super.addErrorCode(errorCode);
    }

    public ServiceUpdateException(String message, DeploymentErrorCode errorCode,
                                  DeploymentErrorType errorType) {
        super(message);
        super.addErrorCode(COMPONENT_UPDATE_ERROR);
        super.addErrorCode(errorCode);
        super.addErrorType(errorType);
    }

    public ServiceUpdateException(String message, Throwable e, DeploymentErrorCode errorCode,
                                  DeploymentErrorType errorType) {
        super(message, e);
        super.addErrorCode(COMPONENT_UPDATE_ERROR);
        super.addErrorCode(errorCode);
        super.addErrorType(errorType);
    }

    public ServiceUpdateException(Throwable e, DeploymentErrorCode errorCode,
                                  DeploymentErrorType errorType) {
        super(e);
        super.addErrorCode(COMPONENT_UPDATE_ERROR);
        super.addErrorCode(errorCode);
        super.addErrorType(errorType);
    }
}
