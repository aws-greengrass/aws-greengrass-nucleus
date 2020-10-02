/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class ConfigurationUpdateOperation {

    @JsonProperty("MERGE")
    Map valueToMerge;

    @JsonProperty("RESET")
    List<String> pathsToReset;
}

