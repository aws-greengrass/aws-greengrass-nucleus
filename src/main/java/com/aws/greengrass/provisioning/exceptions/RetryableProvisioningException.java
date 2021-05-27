/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.provisioning.exceptions;

public class RetryableProvisioningException extends Exception {

    static final long serialVersionUID = -3387516993124229948L;

    public RetryableProvisioningException(Throwable e) {
        super(e);
    }

    public RetryableProvisioningException(String error) {
        super(error);
    }
}
