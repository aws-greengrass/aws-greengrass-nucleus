/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.model.Deployment;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DEPLOYMENT_DOCUMENT_NOT_VALID;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class InvalidRequestException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidRequestException(String message, Throwable e) {
        super(message, e);
        super.addErrorCode(DEPLOYMENT_DOCUMENT_NOT_VALID);
    }

    public InvalidRequestException(Throwable e) {
        super(e);
        super.addErrorCode(DEPLOYMENT_DOCUMENT_NOT_VALID);
    }

    public InvalidRequestException(String message) {
        super(message);
        super.addErrorCode(DEPLOYMENT_DOCUMENT_NOT_VALID);
    }

    public InvalidRequestException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(DEPLOYMENT_DOCUMENT_NOT_VALID);
        super.addErrorCode(errorCode);
    }

    public InvalidRequestException(String message, DeploymentErrorCode errorCode,
            Deployment.DeploymentType deploymentType) {
        super(message);
        super.addErrorCode(DEPLOYMENT_DOCUMENT_NOT_VALID);
        super.addErrorCode(errorCode);
        super.addErrorType(DeploymentErrorCodeUtils.getDeploymentRequestErrorType(deploymentType));
    }

    public InvalidRequestException(String message, Throwable e, Deployment.DeploymentType deploymentType) {
        super(message, e);
        super.addErrorCode(DEPLOYMENT_DOCUMENT_NOT_VALID);
        super.addErrorType(DeploymentErrorCodeUtils.getDeploymentRequestErrorType(deploymentType));
    }

    @Override
    public InvalidRequestException withErrorContext(Throwable t, DeploymentErrorCode errorCode) {
        super.withErrorContext(t, errorCode);
        return this;
    }
}
