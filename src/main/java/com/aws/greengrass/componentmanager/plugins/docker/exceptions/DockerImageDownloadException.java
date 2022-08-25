/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DOCKER_ERROR;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class DockerImageDownloadException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public DockerImageDownloadException(String message) {
        super(message);
        super.addErrorCode(DOCKER_ERROR);
    }

    public DockerImageDownloadException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(DOCKER_ERROR);
    }

    public DockerImageDownloadException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(DOCKER_ERROR);
        super.addErrorCode(errorCode);
    }

    public DockerImageDownloadException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        super.addErrorCode(DOCKER_ERROR);
        super.addErrorCode(errorCode);
    }
}
