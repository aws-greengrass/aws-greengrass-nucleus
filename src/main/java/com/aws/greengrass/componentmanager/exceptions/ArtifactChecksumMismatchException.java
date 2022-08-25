/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

public class ArtifactChecksumMismatchException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public ArtifactChecksumMismatchException(String message) {
        super(message);
    }

    public ArtifactChecksumMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

    public ArtifactChecksumMismatchException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.getErrorCodes().add(errorCode);
    }
}
