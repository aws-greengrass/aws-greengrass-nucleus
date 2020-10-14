/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.converter;

import com.amazonaws.arn.Arn;
import com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FleetConfiguration;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.deployment.model.PackageInfo;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.amazonaws.services.evergreen.model.ComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS;

public final class DeploymentDocumentConverter {

    public static final String DEFAULT_GROUP_NAME = "DEFAULT";
    public static final Integer NO_OP_TIMEOUT = 0;

    public static final String ANY_VERSION = "*";

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

        // Build configs
        List<DeploymentPackageConfiguration> packageConfigurations =
                buildDeploymentPackageConfigurations(localOverrideRequest, newRootComponents);

        return DeploymentDocument.builder().timestamp(localOverrideRequest.getRequestTimestamp())
                .deploymentId(localOverrideRequest.getRequestId())
                .deploymentPackageConfigurationList(packageConfigurations)
                // Currently we always skip safety check for local deployment to not slow down testing for customers
                // If we make this configurable in local development then we can plug that input in here
                // NO_OP_TIMEOUT is not used since the policy is SKIP_NOTIFY_COMPONENTS
                .componentUpdatePolicy(new ComponentUpdatePolicy(NO_OP_TIMEOUT, SKIP_NOTIFY_COMPONENTS)).groupName(
                        StringUtils.isEmpty(localOverrideRequest.getGroupName()) ? DEFAULT_GROUP_NAME
                                : localOverrideRequest.getGroupName()).build();
    }

    /**
     * Convert {@link FleetConfiguration} to a {@link DeploymentDocument}.
     *
     * @param config config received from Iot cloud
     * @return equivalent {@link DeploymentDocument}
     */
    public static DeploymentDocument convertFromFleetConfiguration(FleetConfiguration config) {
        ComponentUpdatePolicy componentUpdatePolicy =
                new ComponentUpdatePolicy(config.getComponentUpdatePolicy().getTimeout(), ComponentUpdatePolicyAction
                        .fromValue(config.getComponentUpdatePolicy().getAction()));
        DeploymentDocument deploymentDocument = DeploymentDocument.builder().deploymentId(config.getConfigurationArn())
                .timestamp(config.getCreationTimestamp()).failureHandlingPolicy(config.getFailureHandlingPolicy())
                // TODO: Use full featured component update policy and configuration validation policy with timeouts
                .componentUpdatePolicy(componentUpdatePolicy).deploymentPackageConfigurationList(new ArrayList<>())
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

            // Create component config update from the config field for backward compatibility
            // TODO This will be removed along with the function when migrating to new createDeployment API
            ConfigurationUpdateOperation configurationUpdateOperation = new ConfigurationUpdateOperation();
            boolean isConfigUpdate = false;

            Map<String, Object> configuration = pkgInfo.getConfiguration();
            if (configuration.containsKey(ConfigurationUpdateOperation.MERGE_KEY)) {
                isConfigUpdate = true;


                Object mergeVal = configuration.get(ConfigurationUpdateOperation.MERGE_KEY);
                if (mergeVal instanceof Map) {
                    configurationUpdateOperation.setValueToMerge((Map) mergeVal);
                }
            }
            if (configuration.containsKey(ConfigurationUpdateOperation.RESET_KEY)) {
                isConfigUpdate = true;

                Object resetPaths = configuration.get(ConfigurationUpdateOperation.RESET_KEY);
                if (resetPaths instanceof List) {
                    configurationUpdateOperation.setPathsToReset((List<String>) resetPaths);
                }
            }

            deploymentDocument.getDeploymentPackageConfigurationList()
                    .add(new DeploymentPackageConfiguration(pkgName, pkgInfo.isRootComponent(), pkgInfo.getVersion(),
                                                            isConfigUpdate ? null : pkgInfo.getConfiguration(),
                                                            isConfigUpdate ? configurationUpdateOperation : null));
        }
        return deploymentDocument;
    }

    private static List<DeploymentPackageConfiguration> buildDeploymentPackageConfigurations(
            LocalOverrideRequest localOverrideRequest, Map<String, String> newRootComponents) {
        Map<String, DeploymentPackageConfiguration> packageConfigurations;

        // convert Deployment Config from getComponentNameToConfig, which doesn't include root components necessarily
        if (localOverrideRequest.getComponentNameToConfig() == null || localOverrideRequest.getComponentNameToConfig()
                .isEmpty()) {
            packageConfigurations = new HashMap<>();
        } else {
            packageConfigurations = localOverrideRequest.getComponentNameToConfig().entrySet().stream().collect(
                    Collectors.toMap(Map.Entry::getKey,
                                     entry -> new DeploymentPackageConfiguration(entry.getKey(), false, ANY_VERSION,
                                                                                 entry.getValue())));
        }

        if (localOverrideRequest.getConfigurationUpdate() != null) {
            localOverrideRequest.getConfigurationUpdate().forEach((componentName, configUpdate) -> {
                packageConfigurations.computeIfAbsent(componentName, DeploymentPackageConfiguration::new);
                packageConfigurations.get(componentName).setConfigurationUpdateOperation(configUpdate);
                packageConfigurations.get(componentName).setResolvedVersion(ANY_VERSION);
            });
        }

        // Add to or update root component with version in the configuration lists
        newRootComponents.forEach((rootComponentName, version) -> {
            DeploymentPackageConfiguration pkg =
                    packageConfigurations.computeIfAbsent(rootComponentName, DeploymentPackageConfiguration::new);
            pkg.setResolvedVersion(version);
            pkg.setRootComponent(true);
        });
        return new ArrayList<>(packageConfigurations.values());
    }
}
