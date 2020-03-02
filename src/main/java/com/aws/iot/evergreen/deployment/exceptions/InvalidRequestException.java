/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.exceptions;

import java.util.Map;

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
