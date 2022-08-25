/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.exceptions;

import static com.aws.greengrass.deployment.errorcode.DeploymentErrorCode.HASHING_ALGORITHM_UNAVAILABLE;

public class HashingAlgorithmUnavailableException extends PackageLoadingException {
    static final long serialVersionUID = -3387516993124229948L;

    public HashingAlgorithmUnavailableException(String message) {
        super(message);
        super.addErrorCode(HASHING_ALGORITHM_UNAVAILABLE);
    }

    public HashingAlgorithmUnavailableException(String message, Throwable cause) {
        super(message, cause);
        super.addErrorCode(HASHING_ALGORITHM_UNAVAILABLE);
    }
}
