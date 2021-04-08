/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

public class MissingRequiredCapabilitiesException extends DeploymentException {

    static final long serialVersionUID = -3387516993124229948L;

    public MissingRequiredCapabilitiesException(String message) {
        super(message);
    }
}
