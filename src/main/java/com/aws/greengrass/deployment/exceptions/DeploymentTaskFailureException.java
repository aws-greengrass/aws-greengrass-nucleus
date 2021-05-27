/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class DeploymentTaskFailureException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public DeploymentTaskFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeploymentTaskFailureException(Throwable throwable) {
        super(throwable);

    }

    public DeploymentTaskFailureException(String s) {
        super(s);
    }
}
