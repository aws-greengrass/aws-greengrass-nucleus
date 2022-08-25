/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DOWNLOAD_DEPLOYMENT_DOCUMENT_ERROR;

/**
 * Exception for failing to download the deployment document from cloud for IoT Jobs/Shadow Deployment. Possible causes
 * could be network errors, cloud service errors, device side errors, and I/O errors.
 */
public class RetryableDeploymentDocumentDownloadException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public RetryableDeploymentDocumentDownloadException(Throwable e) {
        super(e);
        super.getErrorCodes().add(DOWNLOAD_DEPLOYMENT_DOCUMENT_ERROR);
    }

    public RetryableDeploymentDocumentDownloadException(String message) {
        super(message);
        super.getErrorCodes().add(DOWNLOAD_DEPLOYMENT_DOCUMENT_ERROR);
    }

    public RetryableDeploymentDocumentDownloadException(String message, Throwable e) {
        super(message, e);
        super.getErrorCodes().add(DOWNLOAD_DEPLOYMENT_DOCUMENT_ERROR);
    }

    @Override
    public RetryableDeploymentDocumentDownloadException withErrorContext(String className,
                                                                         DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(className, errorCode);
        return this;
    }
}
