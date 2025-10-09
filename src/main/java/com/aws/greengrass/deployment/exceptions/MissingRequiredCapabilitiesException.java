/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.NUCLEUS_MISSING_REQUIRED_CAPABILITIES;

public class MissingRequiredCapabilitiesException extends DeploymentException {

    static final long serialVersionUID = -3387516993124229948L;

    public MissingRequiredCapabilitiesException(String message) {
        super(message);
        super.addErrorCode(NUCLEUS_MISSING_REQUIRED_CAPABILITIES);
    }
}
