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
public enum Type {
    HARD("HARD"),
    SOFT("SOFT");

    private String value;
}
