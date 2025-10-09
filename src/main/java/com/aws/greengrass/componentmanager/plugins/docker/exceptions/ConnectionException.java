/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.NETWORK_ERROR;

public class ConnectionException extends DockerImageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public ConnectionException(String message) {
        super(message);
        super.addErrorCode(NETWORK_ERROR);
    }

    public ConnectionException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(NETWORK_ERROR);
    }
}