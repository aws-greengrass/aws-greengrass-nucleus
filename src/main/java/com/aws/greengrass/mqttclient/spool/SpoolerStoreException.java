/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

public class SpoolerStoreException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public SpoolerStoreException(String message) {
        super(message);
    }

    public SpoolerStoreException(String message, Throwable cause) {
        super(message, cause);
    }

    public SpoolerStoreException(Throwable cause) {
        super(cause);
    }
}
