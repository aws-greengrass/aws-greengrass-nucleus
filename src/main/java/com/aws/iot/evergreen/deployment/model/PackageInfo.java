/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.model;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@JsonSerialize
@JsonDeserialize
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class PackageInfo {

    private boolean rootComponent;

    private String version;

    private Map<String, Object> configuration = new HashMap<>();
}
