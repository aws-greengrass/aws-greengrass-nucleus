/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

public enum FailureHandlingPolicy {
    ROLLBACK("ROLLBACK"),
    DO_NOTHING("DO_NOTHING");

    private final String failureHandlingPolicy;

    FailureHandlingPolicy(final String val) {
        this.failureHandlingPolicy = val;
    }
}
