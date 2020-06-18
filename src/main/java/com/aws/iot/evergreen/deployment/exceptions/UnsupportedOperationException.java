/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.exceptions;

public class UnsupportedOperationException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public UnsupportedOperationException(String message) {
        super(message);
    }

    public UnsupportedOperationException(Throwable e) {
        super(e);
    }
}
