/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.model;

import com.amazonaws.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Class to model the deployment configuration coming from cloud, local, or any other sources that can trigger a
 * deployment.
 *
 * <p>JSON Annotations are only in tests to easily generate this model from a JSON file. They are not part of business
 * logic.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
@EqualsAndHashCode
public class DeploymentDocument {

    @JsonProperty("DeploymentId")
    private String deploymentId;

    @JsonProperty("Packages")
    private List<DeploymentPackageConfiguration> deploymentPackageConfigurationList;

    @JsonProperty("GroupName")
    private String groupName;

    @Setter
    @JsonProperty("Timestamp")
    private Long timestamp;

    @JsonProperty("FailureHandlingPolicy")
    @Builder.Default
    private FailureHandlingPolicy failureHandlingPolicy = FailureHandlingPolicy.ROLLBACK;

    @JsonProperty("ComponentUpdatePolicy")
    @Builder.Default
    private ComponentUpdatePolicy componentUpdatePolicy = new ComponentUpdatePolicy();

    @JsonProperty("ConfigurationValidationPolicy")
    @Builder.Default
    private DeploymentConfigurationValidationPolicy configurationValidationPolicy =
            new DeploymentConfigurationValidationPolicy();

    /**
     * Get a list of root component names from the deploymentPackageConfigurationList.
     *
     * @return list of root component names.
     */
    @JsonIgnore
    public List<String> getRootPackages() {
        if (deploymentPackageConfigurationList == null || deploymentPackageConfigurationList.isEmpty()) {
            return Collections.emptyList();
        }
        return deploymentPackageConfigurationList.stream()
                                                 .filter(DeploymentPackageConfiguration::isRootComponent)
                                                 .map(DeploymentPackageConfiguration::getPackageName)
                                                 .collect(Collectors.toList());
    }
}
