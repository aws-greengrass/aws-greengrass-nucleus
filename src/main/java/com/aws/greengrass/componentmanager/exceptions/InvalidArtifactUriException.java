/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import java.util.Arrays;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.ARTIFACT_URI_NOT_VALID;

public class InvalidArtifactUriException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public InvalidArtifactUriException(String message) {
        super(message, ARTIFACT_URI_NOT_VALID);
    }

    public InvalidArtifactUriException(String message, Throwable cause) {
        super(message, cause, ARTIFACT_URI_NOT_VALID);
    }

    public InvalidArtifactUriException(String message, DeploymentErrorCode errorCode) {
        super(message, Arrays.asList(ARTIFACT_URI_NOT_VALID, errorCode));
    }
}
