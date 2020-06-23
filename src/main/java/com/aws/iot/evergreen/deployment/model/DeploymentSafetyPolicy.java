/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

public enum DeploymentSafetyPolicy {
    CHECK_SAFETY("CHECK_SAFETY"),
    SKIP_SAFETY_CHECK("SKIP_SAFETY_CHECK");

    private String deploymentSafetyPolicy;

    DeploymentSafetyPolicy(final String val) {
        this.deploymentSafetyPolicy = val;
    }
}
