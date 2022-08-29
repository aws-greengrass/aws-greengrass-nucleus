/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;

import java.util.List;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.ARTIFACT_DOWNLOAD_ERROR;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class PackageDownloadException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageDownloadException(String message) {
        super(message);
        super.addErrorCode(ARTIFACT_DOWNLOAD_ERROR);
    }

    public PackageDownloadException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(ARTIFACT_DOWNLOAD_ERROR);
    }

    public PackageDownloadException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(ARTIFACT_DOWNLOAD_ERROR);
        super.addErrorCode(errorCode);
    }

    public PackageDownloadException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        super.addErrorCode(ARTIFACT_DOWNLOAD_ERROR);
        super.addErrorCode(errorCode);
    }

    public PackageDownloadException(String message, List<DeploymentErrorCode> errorCodes) {
        super(message);
        super.addErrorCode(ARTIFACT_DOWNLOAD_ERROR);
        for (DeploymentErrorCode errorCode : errorCodes) {
            super.addErrorCode(errorCode);
        }
    }

    @Override
    public PackageDownloadException withErrorContext(String className, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(className, errorCode);
        return this;
    }
}
