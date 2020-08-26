/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.common;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

@JsonDeserialize(builder = Platform.PlatformBuilder.class)
@Builder
@Value
public class Platform {
    public static final String ALL_KEYWORD = "all";
    String os;
    String osVersion;
    String architecture;

    @JsonPOJOBuilder(withPrefix = "")
    public static class PlatformBuilder {
    }
}
