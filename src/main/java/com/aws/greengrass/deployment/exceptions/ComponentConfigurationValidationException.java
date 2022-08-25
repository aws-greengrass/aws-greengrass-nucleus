/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

public class ComponentConfigurationValidationException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public ComponentConfigurationValidationException(String message) {
        super(message);
    }

    public ComponentConfigurationValidationException(Throwable e) {
        super(e);
    }

    public ComponentConfigurationValidationException(Throwable e, DeploymentErrorCode errorCode) {
        super(e);
        super.getErrorCodes().add(errorCode);
    }

    public ComponentConfigurationValidationException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.getErrorCodes().add(errorCode);
    }
}

