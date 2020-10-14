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

    static final String MERGE_KEY = "MERGE";
    static final String RESET_KEY = "RESET";

    @JsonProperty(MERGE_KEY)
    Map valueToMerge;

    @JsonProperty(RESET_KEY)
    List<String> pathsToReset;
}

