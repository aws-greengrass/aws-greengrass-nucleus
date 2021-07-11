/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.templating;

public class IllegalTransformerException extends Exception {
    private static final long serialVersionUID = 5974408401386072124L;

    public IllegalTransformerException(String message) {
        super(message);
    }

    public IllegalTransformerException(Throwable e) {
        super(e);
    }
}
