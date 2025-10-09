/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.GET_ECR_CREDENTIAL_ERROR;

public class RegistryAuthException extends DockerImageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public RegistryAuthException(String message) {
        super(message);
        super.addErrorCode(GET_ECR_CREDENTIAL_ERROR);
    }

    public RegistryAuthException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(GET_ECR_CREDENTIAL_ERROR);
    }
}