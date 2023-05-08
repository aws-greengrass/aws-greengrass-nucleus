/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class DeploymentCancellationException extends Exception {

    static final long serialVersionUID = -3387516993124229948L;

    public DeploymentCancellationException(String message, Throwable e) {
        super(message, e);
    }

    public DeploymentCancellationException(Throwable e) {
        super(e);
    }

    public DeploymentCancellationException(String message) {
        super(message);
    }
}
