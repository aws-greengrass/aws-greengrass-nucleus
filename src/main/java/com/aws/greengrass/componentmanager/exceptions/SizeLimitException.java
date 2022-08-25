/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.DISK_SPACE_CRITICAL;

public class SizeLimitException extends PackageDownloadException {
    static final long serialVersionUID = -3387516993124229948L;

    public SizeLimitException(String message) {
        super(message);
        super.addErrorCode(DISK_SPACE_CRITICAL);
    }

    public SizeLimitException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(DISK_SPACE_CRITICAL);
    }
}
