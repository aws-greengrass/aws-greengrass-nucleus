/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Class to represent info about how the kernel should run lifecycle commands.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class RunWith {

    @JsonProperty("PosixUser")
    private String posixUser;

    @JsonProperty("PosixGroup")
    private String posixGroup;

    @JsonProperty("WindowsUser")
    private String windowsUser;
}
