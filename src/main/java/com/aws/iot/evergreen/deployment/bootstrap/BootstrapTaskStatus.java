/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.bootstrap;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@Data
@EqualsAndHashCode
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class BootstrapTaskStatus implements Serializable {
    private static final long serialVersionUID = 0L;

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
