/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.componentmanager.plugins.docker.exceptions;


import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DOCKER_IMAGE_NOT_VALID;

public class InvalidImageOrAccessDeniedException extends DockerImageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidImageOrAccessDeniedException(String message) {
        super(message);
        super.addErrorCode(DOCKER_IMAGE_NOT_VALID);
    }

    public InvalidImageOrAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(DOCKER_IMAGE_NOT_VALID);
    }
}
