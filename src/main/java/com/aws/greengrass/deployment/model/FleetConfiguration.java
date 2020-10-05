/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.amazonaws.services.evergreen.model.ComponentUpdatePolicy;
import com.amazonaws.services.evergreen.model.ConfigurationValidationPolicy;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@JsonDeserialize
@JsonSerialize
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
public class FleetConfiguration {

    @Setter
    private String configurationArn;

    private Map<String, PackageInfo> packages;

    private List<String> platforms;

    @Setter
    private Long creationTimestamp;

    private FailureHandlingPolicy failureHandlingPolicy;

    private ComponentUpdatePolicy componentUpdatePolicy;

    private ConfigurationValidationPolicy configurationValidationPolicy;
}
