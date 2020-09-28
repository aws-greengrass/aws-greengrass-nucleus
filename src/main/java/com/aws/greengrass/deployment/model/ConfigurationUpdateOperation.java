/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Value;

@Value
public class ConfigurationUpdateOperation {
    ConfigurationUpdateOperationType operationType;

    String path;

    JsonNode value;
}

