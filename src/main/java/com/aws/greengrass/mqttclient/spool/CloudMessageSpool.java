/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import com.aws.greengrass.mqttclient.PublishRequest;

public interface CloudMessageSpool {

    PublishRequest getMessageById(Long id);

    void removeMessageById(Long id);

    void add(Long id, PublishRequest request);

    SpoolerStorageType getSpoolerStorageType();
}
