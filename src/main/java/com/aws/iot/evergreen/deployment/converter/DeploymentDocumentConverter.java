/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.deployment.converter;

import com.amazonaws.arn.Arn;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentPackageConfiguration;
import com.aws.iot.evergreen.deployment.model.DeploymentSafetyPolicy;
import com.aws.iot.evergreen.deployment.model.FleetConfiguration;
import com.aws.iot.evergreen.deployment.model.LocalOverrideRequest;
import com.aws.iot.evergreen.deployment.model.PackageInfo;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public final class DeploymentDocumentConverter {

    public static final String DEFAULT_GROUP_NAME = "DEFAULT";

    private static final String ANY_VERSION = "*";

    private DeploymentDocumentConverter() {
        // So that this can't be initialized
    }

    /**
     * Convert to a DeploymentDocument from a LocalOverrideRequest and the current running root Components.
     *
     * @param localOverrideRequest  local override request
     * @param runningRootComponents current running root component name to version
     * @return a converted DeploymentDocument
     */
    public static DeploymentDocument convertFromLocalOverrideRequestAndRoot(LocalOverrideRequest localOverrideRequest,
                                                                            Map<String, String> runningRootComponents) {

        // copy over existing root components
        Map<String, String> newRootComponents = new HashMap<>(runningRootComponents);

        // remove
        List<String> componentsToRemove = localOverrideRequest.getComponentsToRemove();
        if (componentsToRemove != null) {
            componentsToRemove.forEach(newRootComponents::remove);
        }

        // add or update
        Map<String, String> componentsToMerge = localOverrideRequest.getComponentsToMerge();
        if (componentsToMerge != null) {
            componentsToMerge.forEach(newRootComponents::put);
        }

        List<String> rootPackages = new ArrayList<>(newRootComponents.keySet());

        // Build configs
        List<DeploymentPackageConfiguration> packageConfigurations =
                buildDeploymentPackageConfigurations(localOverrideRequest, newRootComponents);

        return DeploymentDocument.builder().timestamp(localOverrideRequest.getRequestTimestamp())
                .deploymentId(localOverrideRequest.getRequestId()).rootPackages(rootPackages)
                .deploymentPackageConfigurationList(packageConfigurations)
                // Currently we always skip safety check for local deployment to not slow down testing for customers
                // If we make this configurable in local development then we can plug that input in here
                .deploymentSafetyPolicy(DeploymentSafetyPolicy.SKIP_SAFETY_CHECK)
                .groupName(StringUtils.isEmpty(localOverrideRequest.getGroupName()) ? DEFAULT_GROUP_NAME
                        : localOverrideRequest.getGroupName()).build();
    }

    /**
     * Convert {@link FleetConfiguration} to a {@link DeploymentDocument}.
     * @param config config received from Iot cloud
     * @return equivalent {@link DeploymentDocument}
     */
    public static DeploymentDocument convertFromFleetConfiguration(FleetConfiguration config) {
        DeploymentDocument deploymentDocument = DeploymentDocument.builder().deploymentId(config.getConfigurationArn())
                .timestamp(config.getCreationTimestamp()).failureHandlingPolicy(config.getFailureHandlingPolicy())
                .rootPackages(new ArrayList<>()).deploymentPackageConfigurationList(new ArrayList<>())
                // TODO : When Fleet Configuration Service supports deployment safety policy,
                //  read this from the fleet config
                .deploymentSafetyPolicy(DeploymentSafetyPolicy.CHECK_SAFETY)
                .build();

        String groupName;
        try {
            // Resource name formats:
            // configuration:thing/<thing-name>:version
            // configuration:thinggroup/<thing-group-name>:version
            groupName = Arn.fromString(config.getConfigurationArn()).getResource().getResource();
        } catch (IllegalArgumentException e) {
            groupName = config.getConfigurationArn();
        }
        deploymentDocument.setGroupName(groupName);

        if (config.getPackages() == null) {
            return deploymentDocument;
        }
        for (Map.Entry<String, PackageInfo> entry : config.getPackages().entrySet()) {
            String pkgName = entry.getKey();
            PackageInfo pkgInfo = entry.getValue();
            if (pkgInfo.isRootComponent()) {
                deploymentDocument.getRootPackages().add(pkgName);
            }
            deploymentDocument.getDeploymentPackageConfigurationList()
                    .add(new DeploymentPackageConfiguration(pkgName, pkgInfo.isRootComponent(), pkgInfo.getVersion(),
                            pkgInfo.getConfiguration()));
        }
        return deploymentDocument;
    }

    private static List<DeploymentPackageConfiguration> buildDeploymentPackageConfigurations(
            LocalOverrideRequest localOverrideRequest, Map<String, String> newRootComponents) {
        List<DeploymentPackageConfiguration> packageConfigurations;

        // convert Deployment Config from getComponentNameToConfig, which doesn't include root components necessarily
        if (localOverrideRequest.getComponentNameToConfig() == null || localOverrideRequest.getComponentNameToConfig()
                .isEmpty()) {
            packageConfigurations = new ArrayList<>();
        } else {
            packageConfigurations = localOverrideRequest.getComponentNameToConfig().entrySet().stream()
                    .map(entry -> new DeploymentPackageConfiguration(entry.getKey(), false, ANY_VERSION,
                            entry.getValue()))
                    .collect(Collectors.toList());
        }
        // Add to or update root component with version in the configuration lists
        newRootComponents.forEach((rootComponentName, version) -> {
            Optional<DeploymentPackageConfiguration> optionalConfiguration = packageConfigurations.stream()
                    .filter(packageConfiguration -> packageConfiguration.getPackageName().equals(rootComponentName))
                    .findAny();

            if (optionalConfiguration.isPresent()) {
                // if found, update the version requirement to be equal to the requested version
                optionalConfiguration.get().setResolvedVersion(version);
                optionalConfiguration.get().setRootComponent(true);
            } else {
                // if not found, create it with version requirement as the requested version
                packageConfigurations.add(new DeploymentPackageConfiguration(rootComponentName, true, version, null));
            }
        });
        return packageConfigurations;
    }
}
