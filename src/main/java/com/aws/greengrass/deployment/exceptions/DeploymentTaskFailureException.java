/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class DeploymentTaskFailureException extends DeploymentException {
    static final long serialVersionUID = -3387516993124229948L;

    public DeploymentTaskFailureException(Throwable e) {
        super(e);
    }

    public DeploymentTaskFailureException(String message, Throwable e) {
        super(message, e);
    }

    public DeploymentTaskFailureException(String message) {
        super(message);
    }
}
