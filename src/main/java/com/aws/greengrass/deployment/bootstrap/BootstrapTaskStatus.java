/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.bootstrap;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Data
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PACKAGE)
@NoArgsConstructor(access = AccessLevel.PACKAGE)
public class BootstrapTaskStatus {
    private String componentName;
    private ExecutionStatus status;
    private int exitCode;

    public BootstrapTaskStatus(String name) {
        this.componentName = name;
        this.status = ExecutionStatus.PENDING;
    }

    public enum ExecutionStatus {
        PENDING, DONE
    }
}
