/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.Getter;

@JsonSerialize
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NameVersionPair {
    @JsonProperty("Name")
    @Getter
    String packageName;

    @JsonProperty("Version")
    @Getter
    String version;
}
