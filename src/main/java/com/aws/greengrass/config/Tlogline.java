/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
class Tlogline {
    @JsonProperty("TS")
    long timestamp;
    @JsonProperty("TP")
    String[] topicPath;
    @JsonProperty("W")
    WhatHappened action;
    @JsonProperty("V")
    Object value;
}
