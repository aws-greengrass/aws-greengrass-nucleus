/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

import java.util.Arrays;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_UPDATE_ERROR;

public class ServiceUpdateException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public ServiceUpdateException(String message) {
        super(message, COMPONENT_UPDATE_ERROR);
    }

    public ServiceUpdateException(Throwable e) {
        super(e, COMPONENT_UPDATE_ERROR);
    }

    public ServiceUpdateException(String message, DeploymentErrorCode errorCode) {
        super(message, Arrays.asList(COMPONENT_UPDATE_ERROR, errorCode));
    }

    public ServiceUpdateException(Throwable e, DeploymentErrorCode errorCode) {
        super(e, Arrays.asList(COMPONENT_UPDATE_ERROR, errorCode));
    }

    public ServiceUpdateException(String message, Throwable e, DeploymentErrorCode errorCode) {
        super(message, e, Arrays.asList(COMPONENT_UPDATE_ERROR, errorCode));
    }
}
