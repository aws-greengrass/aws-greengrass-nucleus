/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment.model;

import com.amazonaws.arn.Arn;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @JsonProperty("FailureHandlingPolicy")
    private FailureHandlingPolicy failureHandlingPolicy;

    @JsonProperty("DeploymentSafetyPolicy")
    private DeploymentSafetyPolicy deploymentSafetyPolicy;

    /**
     * Constructor to wrap around deployment configurations from Fleet Configuration Service.
     *
     * @param config Fleet Configuration
     */
    public DeploymentDocument(FleetConfiguration config) {
        deploymentId = config.getConfigurationArn();
        timestamp = config.getCreationTimestamp();
        failureHandlingPolicy = config.getFailureHandlingPolicy();
        // TODO : When Fleet Configuration Service supports deployment safety policy, read this from the fleet config
        deploymentSafetyPolicy = DeploymentSafetyPolicy.CHECK_SAFETY;
        rootPackages = new ArrayList<>();
        deploymentPackageConfigurationList = new ArrayList<>();

        try {
            // Example formats: thing/<thing-name>, thinggroup/<thing-group-name>
            groupName = Arn.fromString(deploymentId).getResource().getResource();
        } catch (IllegalArgumentException e) {
            groupName = deploymentId;
        }

        if (config.getPackages() == null) {
            return;
        }
        for (Map.Entry<String, PackageInfo> entry : config.getPackages().entrySet()) {
            String pkgName = entry.getKey();
            PackageInfo pkgInfo = entry.getValue();
            if (pkgInfo.isRootComponent()) {
                rootPackages.add(pkgName);
            }
            deploymentPackageConfigurationList.add(new DeploymentPackageConfiguration(pkgName, pkgInfo.getVersion(),
                    pkgInfo.getConfiguration()));
        }
    }
}
