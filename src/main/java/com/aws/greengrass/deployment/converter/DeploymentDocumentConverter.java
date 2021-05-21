/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

/*
  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
  * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment.converter;

import com.amazon.aws.iot.greengrass.configuration.common.ComponentUpdate;
import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.amazon.aws.iot.greengrass.configuration.common.ConfigurationUpdate;
import com.aws.greengrass.deployment.exceptions.InvalidRequestException;
import com.aws.greengrass.deployment.model.ComponentUpdatePolicy;
import com.aws.greengrass.deployment.model.ConfigurationUpdateOperation;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.deployment.model.DeploymentPackageConfiguration;
import com.aws.greengrass.deployment.model.FailureHandlingPolicy;
import com.aws.greengrass.deployment.model.LocalOverrideRequest;
import com.aws.greengrass.deployment.model.RunWith;
import com.aws.greengrass.deployment.model.SystemResourceLimits;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction;
import software.amazon.awssdk.services.greengrassv2.model.DeploymentConfigurationValidationPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import static com.aws.greengrass.deployment.DynamicComponentConfigurationValidator.DEFAULT_TIMEOUT_SECOND;
import static software.amazon.awssdk.services.greengrassv2.model.DeploymentComponentUpdatePolicyAction.SKIP_NOTIFY_COMPONENTS;

public final class DeploymentDocumentConverter {
    private static final Logger logger = LogManager.getLogger(DeploymentDocumentConverter.class);

    public static final String LOCAL_DEPLOYMENT_GROUP_NAME = "LOCAL_DEPLOYMENT";
    public static final String THING_GROUP_RESOURCE_NAME_PREFIX = "thinggroup/";
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
                .requiredCapabilities(localOverrideRequest.getRequiredCapabilities())
                .failureHandlingPolicy(FailureHandlingPolicy.DO_NOTHING)    // Can't rollback for local deployment
                // Currently we skip update policy check for local deployment to not slow down testing for customers
                // If we make this configurable in local development then we can plug that input in here
                // NO_OP_TIMEOUT is not used since the policy is SKIP_NOTIFY_COMPONENTS
                .configurationValidationPolicy(
                        DeploymentConfigurationValidationPolicy.builder().timeoutInSeconds(DEFAULT_TIMEOUT_SECOND)
                                .build())
                .componentUpdatePolicy(new ComponentUpdatePolicy(NO_OP_TIMEOUT, SKIP_NOTIFY_COMPONENTS))
                .groupName(StringUtils.isEmpty(localOverrideRequest.getGroupName()) ? LOCAL_DEPLOYMENT_GROUP_NAME
                        : THING_GROUP_RESOURCE_NAME_PREFIX + localOverrideRequest.getGroupName()).build();
    }


    private static List<DeploymentPackageConfiguration> buildDeploymentPackageConfigurations(
            LocalOverrideRequest localOverrideRequest, Map<String, String> newRootComponents) {
        Map<String, DeploymentPackageConfiguration> packageConfigurations = new HashMap<>();

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

        if (localOverrideRequest.getComponentToRunWithInfo() != null) {
            localOverrideRequest.getComponentToRunWithInfo().forEach((componentName, runWithInfo) -> {
                if (runWithInfo != null) {
                    packageConfigurations.computeIfAbsent(componentName, DeploymentPackageConfiguration::new);
                    RunWith runWith = RunWith.builder().posixUser(runWithInfo.getPosixUser())
                            .systemResourceLimits(convertSystemResourceLimits(runWithInfo.getSystemResourceLimits()))
                            .build();
                    packageConfigurations.get(componentName).setRunWith(runWith);
                }
            });
        }
        return new ArrayList<>(packageConfigurations.values());
    }

    /**
     * Converts deployment configuration {@link Configuration} that is generated by CreateDeployment and gets sent down
     * via IoT Job and shadow to the Nucleus's core {@link DeploymentDocument}.
     *
     * @param config Fleet configuration that is generated by CreateDeployment and gets sent down via IoT Job and
     *               shadow
     * @return Nucleus's core {@link DeploymentDocument}
     * @throws InvalidRequestException if failed to parsing deployment document from configuration.
     */
    public static DeploymentDocument convertFromDeploymentConfiguration(Configuration config)
            throws InvalidRequestException {

        DeploymentDocument.DeploymentDocumentBuilder builder =
                DeploymentDocument.builder().configurationArn(config.getConfigurationArn())
                        .deploymentId(config.getDeploymentId())
                        .requiredCapabilities(config.getRequiredCapabilities())
                        .deploymentPackageConfigurationList(convertComponents(config.getComponents()))
                        .groupName(parseGroupNameFromConfigurationArn(config)).timestamp(config.getCreationTimestamp());
        if (config.getFailureHandlingPolicy() == null) {
            // FailureHandlingPolicy should be provided per contract with CreateDeployment API.
            // However if it is not, device could proceed with default for resilience.
            logger.atWarn().log("FailureHandlingPolicy should be provided but is not provided. "
                                        + "Proceeding with default failure handling policy.");
        } else {
            builder.failureHandlingPolicy(convertFailureHandlingPolicy(config.getFailureHandlingPolicy()));
        }

        if (config.getComponentUpdatePolicy() == null) {
            // ComponentUpdatePolicy should be provided per contract with CreateDeployment API.
            // However if it is not, device could proceed with default for resilience.
            logger.atWarn().log("ComponentUpdatePolicy should be provided but is not provided. "
                                        + "Proceeding with default failure handling policy.");
        } else {
            builder.componentUpdatePolicy(convertComponentUpdatePolicy(config.getComponentUpdatePolicy()));
        }

        if (config.getConfigurationValidationPolicy() == null) {
            // ConfigurationValidationPolicy should be provided per contract with CreateDeployment API.
            // However if it is not, device could proceed with default for resilience.
            logger.atWarn().log("ConfigurationValidationPolicy should be provided but is not provided. "
                    + "Proceeding with default failure handling policy.");
        } else {
            builder.configurationValidationPolicy(convertConfigurationValidationPolicy(
                    config.getConfigurationValidationPolicy())
            );
        }

        return builder.build();
    }

    private static String parseGroupNameFromConfigurationArn(Configuration config) {
        String groupName;
        try {
            // ConfigurationArn formats:
            // configuration:thing/<thing-name>
            // configuration:thinggroup/<thing-group-name>
            groupName = Arn.fromString(config.getConfigurationArn()).resource().resource();
        } catch (IllegalArgumentException e) {
            // so that it can proceed, rather than fail, when the format of configurationArn is wrong.
            groupName = config.getConfigurationArn();
        }
        return groupName;
    }

    private static List<DeploymentPackageConfiguration> convertComponents(
            @Nullable Map<String, ComponentUpdate> components) throws InvalidRequestException {
        if (components == null || components.isEmpty()) {
            return Collections.emptyList();
        }
        List<DeploymentPackageConfiguration> deploymentPackageConfiguration = new ArrayList<>();
        for (Map.Entry<String, ComponentUpdate> e : components.entrySet()) {
            deploymentPackageConfiguration.add(convertComponent(e.getKey(), e.getValue()));
        }
        return deploymentPackageConfiguration;
    }

    private static DeploymentPackageConfiguration convertComponent(String componentName,
            ComponentUpdate componentUpdate) throws InvalidRequestException {

        if (Utils.isEmpty(componentName)) {
            throw new InvalidRequestException("Target component name is empty");
        }

        if (componentUpdate == null || componentUpdate.getVersion() == null) {
            throw new InvalidRequestException("Version for target component " + componentName + " is empty");
        }

        DeploymentPackageConfiguration.DeploymentPackageConfigurationBuilder builder =
                DeploymentPackageConfiguration.builder().packageName(componentName)
                .resolvedVersion(componentUpdate.getVersion().getValue())
                .rootComponent(true) // As of now, CreateDeployment API only gives root component
                .configurationUpdateOperation(
                        convertComponentUpdateOperation(componentUpdate.getConfigurationUpdate()));
        builder = builder.runWith(RunWith.builder()
                .posixUser(componentUpdate.getRunWith() == null ? null : componentUpdate.getRunWith().getPosixUser())
                .build());
        return builder.build();
    }

    /**
     * Convert configuration update from Cloud/Device shared model to the device-side model.
     * @param configurationUpdate   common model shared between cloud and device
     * @return  device-side model for configuration update
     */
    public static ConfigurationUpdateOperation convertComponentUpdateOperation(
            @Nullable ConfigurationUpdate configurationUpdate) {
        if (configurationUpdate == null) {
            return null;
        }

        Map mapToMerge = null;
        if (configurationUpdate.getMerge() != null) {
            mapToMerge = SerializerFactory.getFailSafeJsonObjectMapper()
                    .convertValue(configurationUpdate.getMerge(), Map.class);
        }

        return new ConfigurationUpdateOperation(mapToMerge, configurationUpdate.getReset());

    }

    private static ComponentUpdatePolicy convertComponentUpdatePolicy(
            @Nonnull com.amazon.aws.iot.greengrass.configuration.common.ComponentUpdatePolicy componentUpdatePolicy) {
        ComponentUpdatePolicy converted = new ComponentUpdatePolicy();

        if (componentUpdatePolicy.getTimeout() != null) {
            converted.setTimeout(componentUpdatePolicy.getTimeout());
        }

        if (componentUpdatePolicy.getAction() != null) {
            converted.setComponentUpdatePolicyAction(
                    DeploymentComponentUpdatePolicyAction.fromValue(componentUpdatePolicy.getAction().name()));
        }

        return converted;
    }

    private static DeploymentConfigurationValidationPolicy convertConfigurationValidationPolicy(
            @Nonnull  com.amazon.aws.iot.greengrass.configuration.common.ConfigurationValidationPolicy
                    configurationValidationPolicy) {

        DeploymentConfigurationValidationPolicy.Builder converted = DeploymentConfigurationValidationPolicy.builder();
        if (configurationValidationPolicy.getTimeout() != null) {
            converted.timeoutInSeconds(configurationValidationPolicy.getTimeout());
        }
        return converted.build();
    }

    private static FailureHandlingPolicy convertFailureHandlingPolicy(
            @Nonnull com.amazon.aws.iot.greengrass.configuration.common.FailureHandlingPolicy failureHandlingPolicy) {

        return FailureHandlingPolicy.valueOf(failureHandlingPolicy.name());
    }

    private static SystemResourceLimits convertSystemResourceLimits(
            software.amazon.awssdk.aws.greengrass.model.SystemResourceLimits resourceLimits) {
        if (resourceLimits == null || resourceLimits.getLinux() == null) {
            return null;
        }
        return new SystemResourceLimits(
                new SystemResourceLimits.LinuxSystemResourceLimits(
                        resourceLimits.getLinux().getMemory(), resourceLimits.getLinux().getCpu()));
    }
}
