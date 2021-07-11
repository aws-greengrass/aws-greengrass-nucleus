/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

public class IllegalTransformerException extends Exception {
    public IllegalTransformerException(String message) {
        super(message);
    }

    public IllegalTransformerException(Throwable e) {
        super(e);
    }
}
