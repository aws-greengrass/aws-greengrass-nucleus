/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

public class SpoolerLoadException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public SpoolerLoadException(String message) {
        super(message);
    }

    public SpoolerLoadException(String message, Throwable cause) {
        super(message, cause);
    }

    public SpoolerLoadException(Throwable cause) {
        super(cause);
    }
}
