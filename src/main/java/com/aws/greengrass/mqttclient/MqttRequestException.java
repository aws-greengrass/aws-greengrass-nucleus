/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */


package com.aws.greengrass.mqttclient;

public class MqttRequestException extends Exception {
    static final long serialVersionUID = -3387516993124229948L;

    public MqttRequestException(String message) {
        super(message);
    }

    public MqttRequestException(String message, Throwable cause) {
        super(message, cause);
    }

    public MqttRequestException(Throwable cause) {
        super(cause);
    }
}
