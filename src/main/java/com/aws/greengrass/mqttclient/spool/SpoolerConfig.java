/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.mqttclient.spool;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class SpoolerConfig {
    private SpoolerStorageType storageType;
    private Long spoolSizeInBytes;
    private boolean keepQos0WhenOffline;
    private String persistenceSpoolServiceName;
}
