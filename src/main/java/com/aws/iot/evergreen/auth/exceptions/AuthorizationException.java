/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.auth.exceptions;

public class AuthorizationException extends Exception {
    // TODO: define better exceptions for AuthZ
    static final long serialVersionUID = -3387516993124229948L;

    public AuthorizationException(String message) {
        super(message);
    }

    public AuthorizationException(Throwable e) {
        super(e);
    }
}
