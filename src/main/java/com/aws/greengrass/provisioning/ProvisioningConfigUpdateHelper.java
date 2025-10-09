/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.provisioning;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.provisioning.ProvisionConfiguration.NucleusConfiguration;
import com.aws.greengrass.provisioning.ProvisionConfiguration.SystemConfiguration;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.NonNull;

import java.util.HashMap;
import java.util.Map;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

@AllArgsConstructor
@NoArgsConstructor
public class ProvisioningConfigUpdateHelper {

    private Kernel kernel;

    /**
     * Updates the system configuration values in kernel config as per the given {@link SystemConfiguration}.
     * 
     * @param systemConfiguration {@link SystemConfiguration}
     * @param updateBehavior Update behavior indicating either merge or replace
     */
    public void updateSystemConfiguration(@NonNull SystemConfiguration systemConfiguration,
            @NonNull UpdateBehaviorTree.UpdateBehavior updateBehavior) {
        Map<String, Object> updateMap = new HashMap<>();
        if (systemConfiguration.getCertificateFilePath() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH,
                    systemConfiguration.getCertificateFilePath());
        }
        if (systemConfiguration.getPrivateKeyPath() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH, systemConfiguration.getPrivateKeyPath());
        }
        if (systemConfiguration.getThingName() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_THING_NAME, systemConfiguration.getThingName());
        }
        if (systemConfiguration.getRootCAPath() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH, systemConfiguration.getRootCAPath());
        }
        Topics systemConfig = kernel.getConfig().lookupTopics(SYSTEM_NAMESPACE_KEY);
        systemConfig.updateFromMap(updateMap, new UpdateBehaviorTree(updateBehavior, System.currentTimeMillis()));
    }

    /**
     * Updates the nucleus configuration value in kernel config as per the given {@link NucleusConfiguration}.
     * 
     * @param nucleusConfiguration {@link NucleusConfiguration}
     * @param updateBehavior Update behavior indicating either merge or replace
     */
    public void updateNucleusConfiguration(@NonNull NucleusConfiguration nucleusConfiguration,
            @NonNull UpdateBehaviorTree.UpdateBehavior updateBehavior) {

        Map<String, Object> updateMap = new HashMap<>();
        if (nucleusConfiguration.getAwsRegion() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_AWS_REGION, nucleusConfiguration.getAwsRegion());
        }
        if (nucleusConfiguration.getIotCredentialsEndpoint() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_IOT_CRED_ENDPOINT,
                    nucleusConfiguration.getIotCredentialsEndpoint());
        }
        if (nucleusConfiguration.getIotDataEndpoint() != null) {
            updateMap.put(DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT,
                    nucleusConfiguration.getIotDataEndpoint());
        }
        if (nucleusConfiguration.getIotRoleAlias() != null) {
            updateMap.put(DeviceConfiguration.IOT_ROLE_ALIAS_TOPIC, nucleusConfiguration.getIotRoleAlias());
        }
        String nucleusComponentName = kernel.getContext().get(DeviceConfiguration.class).getNucleusComponentName();
        Topics nucleusConfig = kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, nucleusComponentName, CONFIGURATION_CONFIG_KEY);
        nucleusConfig.updateFromMap(updateMap, new UpdateBehaviorTree(updateBehavior, System.currentTimeMillis()));
    }
}
