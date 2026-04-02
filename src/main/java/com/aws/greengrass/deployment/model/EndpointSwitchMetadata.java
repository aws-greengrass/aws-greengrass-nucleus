/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Metadata persisted during an endpoint-switch deployment to preserve the source IoT data endpoint
 * for status reporting to the source account.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EndpointSwitchMetadata {
    private String sourceIotDataEndpoint;
}
