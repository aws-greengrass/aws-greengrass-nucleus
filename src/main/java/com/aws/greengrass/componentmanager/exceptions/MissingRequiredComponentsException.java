/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.componentmanager.exceptions;


import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;

public class MissingRequiredComponentsException extends DeploymentTaskFailureException {
    static final long serialVersionUID = -3387516993124229948L;

    public MissingRequiredComponentsException(String message) {
        super(message);
    }

    public MissingRequiredComponentsException(String message, Throwable cause) {
        super(message, cause);
    }
}