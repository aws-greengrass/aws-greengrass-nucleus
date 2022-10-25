/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

public class DeploymentRejectedException extends DeploymentException {

    private static final long serialVersionUID = -8212002201272098501L;

    public DeploymentRejectedException(String message, DeploymentErrorCode errorCode) {
        super(message, errorCode);
    }
}
