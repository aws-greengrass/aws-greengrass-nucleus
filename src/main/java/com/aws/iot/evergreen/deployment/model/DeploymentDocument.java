/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Class to model the deployment configuration coming from cloud, local, or any other sources
 * that can trigger a deployment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
public class DeploymentDocument {

    @JsonProperty("DeploymentId")
    String deploymentId;

    @JsonProperty("RootPackages")
    List<String> rootPackages;

    @JsonProperty("Packages")
    List<DeploymentPackageConfiguration> deploymentPackageConfigurationList;

    @JsonProperty("GroupName")
    String groupName;

    @JsonProperty("Timestamp")
    Long timestamp;
}
