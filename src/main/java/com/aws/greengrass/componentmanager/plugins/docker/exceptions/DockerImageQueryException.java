/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DOCKER_IMAGE_QUERY_ERROR;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class DockerImageQueryException extends PackageDownloadException {
    static final long serialVersionUID = -4937605498062357880L;

    public DockerImageQueryException(String message) {
        super(message);
        super.addErrorCode(DOCKER_IMAGE_QUERY_ERROR);
    }

    public DockerImageQueryException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(DOCKER_IMAGE_QUERY_ERROR);
    }

    public DockerImageQueryException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(DOCKER_IMAGE_QUERY_ERROR);
        super.addErrorCode(errorCode);
    }

    public DockerImageQueryException(String message, Throwable cause, DeploymentErrorCode errorCode) {
        super(message, cause);
        super.addErrorCode(DOCKER_IMAGE_QUERY_ERROR);
        super.addErrorCode(errorCode);
    }
}
