/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.USER_NOT_AUTHORIZED_FOR_DOCKER;

public class UserNotAuthorizedForDockerException extends DockerImageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public UserNotAuthorizedForDockerException(String message) {
        super(message);
        super.addErrorCode(USER_NOT_AUTHORIZED_FOR_DOCKER);
    }

    public UserNotAuthorizedForDockerException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(USER_NOT_AUTHORIZED_FOR_DOCKER);
    }
}