/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.COMPONENT_CIRCULAR_DEPENDENCY_ERROR;

public class ComponentVersionNegotiationException extends PackagingException {
    static final long serialVersionUID = -3387516993124229948L;

    public ComponentVersionNegotiationException(String message) {
        super(message);
        super.addErrorCode(COMPONENT_CIRCULAR_DEPENDENCY_ERROR);
    }

    public ComponentVersionNegotiationException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(COMPONENT_CIRCULAR_DEPENDENCY_ERROR);
    }
}
