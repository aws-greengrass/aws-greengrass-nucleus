/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DOCKER_LOGIN_ERROR;

public class DockerLoginException extends DockerImageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public DockerLoginException(String message) {
        super(message);
        super.addErrorCode(DOCKER_LOGIN_ERROR);
    }

    public DockerLoginException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(DOCKER_LOGIN_ERROR);
    }
}