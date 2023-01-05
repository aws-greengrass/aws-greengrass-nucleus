/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Value;

import java.util.Map;

@Value
public class UserProperty {
    String key;
    String value;

    public static UserProperty fromEntry(Map.Entry<String, String> e) {
        return new UserProperty(e.getKey(), e.getValue());
    }
}
