/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;

/**
 * Class to model the deployment configuration coming from cloud, local, or any other sources
 * that can trigger a deployment.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
// TODO: pull this class to a library to share with cloud services. SIM: https://sim.amazon.com/issues/P33788350
public class DeploymentDocument {

    @JsonProperty("DeploymentId")
    private String deploymentId;

    @JsonProperty("RootPackages")
    private List<String> rootPackages;

    @JsonProperty("Packages")
    private List<DeploymentPackageConfiguration> deploymentPackageConfigurationList;

    @JsonProperty("GroupName")
    private String groupName;

    @Setter
    @JsonProperty("Timestamp")
    private Long timestamp;

}
