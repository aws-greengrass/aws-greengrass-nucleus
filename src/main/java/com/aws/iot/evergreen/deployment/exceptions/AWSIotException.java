/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.exceptions;

public class AWSIotException extends Exception {
    public AWSIotException(String message, Throwable e) {
        super(message, e);
    }

    public AWSIotException(Throwable e) {
        super(e);
    }

    public AWSIotException(String message) {
        super(message);
    }
}
