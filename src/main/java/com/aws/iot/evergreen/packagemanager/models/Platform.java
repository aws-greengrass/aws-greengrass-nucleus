/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.packagemanager.models;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Value;

@Data
public class Platform {
    public static final String OS_WINDOWS = "windows";
    public static final String OS_linux = "linux";
    public static final String OS_Darwin = "darwin";

    public static final String OS_UBUNTU = "ubuntu";
    public static final String OS_MACOS = "MacOS";

    public static final String ARCH_AMD64 = "amd64";

    // TODO: decide CamelCase/camelCase for OS, and other json fields
    @JsonProperty("OS")
    String os;

    @JsonProperty("OS.Flavor")
    String osFlavor;

    @JsonProperty("OS.Version")
    String osVersion;

    @JsonProperty("Architecture")
    String architecture;

    @JsonProperty("Architecture.variant")
    String archVariant;
}
