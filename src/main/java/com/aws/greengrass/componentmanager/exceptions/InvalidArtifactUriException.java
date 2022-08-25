/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.ARTIFACT_URI_NOT_VALID;

@SuppressWarnings("checkstyle:MissingJavadocMethod")
public class InvalidArtifactUriException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidArtifactUriException(String message) {
        super(message);
        super.addErrorCode(ARTIFACT_URI_NOT_VALID);
    }

    public InvalidArtifactUriException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(ARTIFACT_URI_NOT_VALID);
    }

    public InvalidArtifactUriException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(ARTIFACT_URI_NOT_VALID);
        super.addErrorCode(errorCode);
    }
}
