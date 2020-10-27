/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class DeploymentException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(Throwable e) {
        super(e);
    }
}
