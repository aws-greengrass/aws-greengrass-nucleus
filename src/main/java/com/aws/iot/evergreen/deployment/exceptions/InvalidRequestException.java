/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.exceptions;

public class InvalidRequestException extends Exception {

    public InvalidRequestException(String message, Throwable e) {
        super(message, e);
    }

    public InvalidRequestException(Throwable e) {
        super(e);
    }

    public InvalidRequestException(String message) {
        super(message);
    }
}
