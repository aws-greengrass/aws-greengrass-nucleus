/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class NonRetryableDeploymentTaskFailureException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public NonRetryableDeploymentTaskFailureException(String message) {
        super(message);
    }

    public NonRetryableDeploymentTaskFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
