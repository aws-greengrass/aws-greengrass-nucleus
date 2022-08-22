/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCodeUtils;
import com.aws.greengrass.deployment.exceptions.DeploymentException;

import java.util.Arrays;
import java.util.List;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.ARTIFACT_DOWNLOAD_ERROR;

public class PackageDownloadException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public PackageDownloadException(String message) {
        super(message, ARTIFACT_DOWNLOAD_ERROR);
    }

    public PackageDownloadException(String message, Throwable cause) {
        super(message, cause, ARTIFACT_DOWNLOAD_ERROR);
    }

    public PackageDownloadException(String message, DeploymentErrorCode errorCode) {
        super(message, Arrays.asList(ARTIFACT_DOWNLOAD_ERROR, errorCode));
    }

    public PackageDownloadException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause, Arrays.asList(ARTIFACT_DOWNLOAD_ERROR, errorCode));
    }

    public PackageDownloadException(String message, List<DeploymentErrorCode> errorCodes) {
        super(message, DeploymentErrorCodeUtils.inlineAddToFront(errorCodes, ARTIFACT_DOWNLOAD_ERROR));
    }

    public PackageDownloadException(String message, Throwable cause, List<DeploymentErrorCode> errorCodes) {
        super(message, cause, DeploymentErrorCodeUtils.inlineAddToFront(errorCodes, ARTIFACT_DOWNLOAD_ERROR));
    }

    @Override
    public PackageDownloadException withErrorContext(Class<? extends Throwable> clazz, DeploymentErrorCode errorCode) {
        errorContext.putIfAbsent(clazz, errorCode);
        return this;
    }
}
