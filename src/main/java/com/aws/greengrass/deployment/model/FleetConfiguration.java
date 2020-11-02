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

/**
 *  This class represents the deployment configuration model that FCS sends down for IOT_JOBS and SHADOWS.
 *  During a deployment, this model gets converted to DeploymentDocument, which is the core device model.
 *
 *  <p>TODO: [P41179644] Move to common model.
 */

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
