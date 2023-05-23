/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.v5;

import lombok.Value;
import java.io.Serializable;

@Value
public class UserProperty implements Serializable {
    String key;
    String value;
}
