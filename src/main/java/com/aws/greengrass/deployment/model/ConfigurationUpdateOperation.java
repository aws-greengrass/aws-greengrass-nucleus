/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ConfigurationUpdateOperation {

    public static final String MERGE_KEY = "MERGE";
    public static final String RESET_KEY = "RESET";

    @JsonProperty(MERGE_KEY)
    Map valueToMerge;

    @JsonProperty(RESET_KEY)
    List<String> pathsToReset;
}

