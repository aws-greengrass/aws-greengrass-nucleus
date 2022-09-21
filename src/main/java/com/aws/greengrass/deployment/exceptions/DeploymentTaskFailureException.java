/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;

public class DeploymentTaskFailureException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public DeploymentTaskFailureException(String message) {
        super(message);
    }

    public DeploymentTaskFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeploymentTaskFailureException(Throwable throwable) {
        super(throwable);
    }

    public DeploymentTaskFailureException(String message, DeploymentErrorCode errorCode) {
        super(message);
        super.addErrorCode(errorCode);
    }

    @Override
    public DeploymentTaskFailureException withErrorContext(Throwable t, DeploymentErrorCode errorCode) {
        super.withErrorContext(t, errorCode);
        return this;
    }
}
