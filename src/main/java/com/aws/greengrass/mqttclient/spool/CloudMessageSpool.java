/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;

public interface CloudMessageSpool {

    PublishRequest getMessageById(long id);

    void removeMessageById(long id);

    void add(long id, PublishRequest request);
}
