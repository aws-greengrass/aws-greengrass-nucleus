/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

/**
 * Exception for failing to download the deployment document from cloud for IoT Jobs/Shadow Deployment.
 * Possible causes could be network errors, cloud service errors, device side errors, and I/O errors.
 */
public class DeploymentDocumentDownloadException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public DeploymentDocumentDownloadException(Throwable e) {
        super(e);
    }

    public DeploymentDocumentDownloadException(String message) {
        super(message);
    }

    public DeploymentDocumentDownloadException(String message, Throwable e) {
        super(message, e);
    }
}
