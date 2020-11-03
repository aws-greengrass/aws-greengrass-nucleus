/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.dependency.Crashable;
import lombok.Value;

@Value
public class UpdateAction {
    String deploymentId;
    boolean ggcRestart;
    Integer timeout;
    Crashable action;
}
