/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentException;

public class DirectoryValidationException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public DirectoryValidationException(String message) {
        super(message);
        super.addErrorCode(DeploymentErrorCode.LAUNCH_DIRECTORY_CORRUPTED);
    }

    public DirectoryValidationException(String message, Throwable throwable) {
        super(message, throwable);
        super.addErrorCode(DeploymentErrorCode.LAUNCH_DIRECTORY_CORRUPTED);
    }
}