/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ConfigurationUpdateOperation {

    @JsonProperty("Operation")
    ConfigurationUpdateOperationType operationType;

    @JsonProperty("Path")
    String path;

    @JsonProperty("Value")
    JsonNode value;
}

