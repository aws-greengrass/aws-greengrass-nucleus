/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.dependency;

import lombok.AllArgsConstructor;

/**
 * Dependency type used for declared service dependencies.
 */
@AllArgsConstructor
public enum DependencyType {
    /**
     * The lifecycle of hard dependencies will impact the lifecycle of the depending service.
     * e.g. the depending service will be restarted by Kernel if any hard dependency errors out and tries to recover.
     */
    HARD("HARD"),

    /**
     * Soft dependencies have independent lifecycle from the depending service.
     * e.g. the depending service can remain in its state if any soft dependency errors out.
     */
    SOFT("SOFT");

    private String value;
}
