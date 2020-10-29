/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import lombok.Builder;
import lombok.Getter;

/**
 * Lifecycle information used to run a service.
 */
@Builder
@Getter
public class RunWith {
    private String user;
    private String group;
    private String shell;
    private boolean isDefault;
}
