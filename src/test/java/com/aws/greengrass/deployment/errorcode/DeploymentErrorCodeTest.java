/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.errorcode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeploymentErrorCodeTest {

    @Test
    void GIVEN_MQTT_CONNECTION_FAILED_WHEN_getErrorType_THEN_returns_NETWORK_ERROR() {
        assertEquals(DeploymentErrorType.NETWORK_ERROR, DeploymentErrorCode.MQTT_CONNECTION_FAILED.getErrorType());
    }

    @Test
    void GIVEN_TLS_HANDSHAKE_FAILURE_WHEN_getErrorType_THEN_returns_NETWORK_ERROR() {
        assertEquals(DeploymentErrorType.NETWORK_ERROR, DeploymentErrorCode.TLS_HANDSHAKE_FAILURE.getErrorType());
    }

    @Test
    void GIVEN_MISSING_MQTT_CONNECT_POLICY_WHEN_getErrorType_THEN_returns_PERMISSION_ERROR() {
        assertEquals(DeploymentErrorType.PERMISSION_ERROR, DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY.getErrorType());
    }
}
