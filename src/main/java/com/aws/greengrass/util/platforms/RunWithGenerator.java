/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;

import java.util.Optional;

/**
 * Generate a {@link RunWith} from a service {@link Topics} configuration. This takes into account the configuration
 * from the service and the default configuration in the kernel.
 */
public interface RunWithGenerator {
    /**
     * Generate a {@link RunWith} containing information that the nucleus needs when executing the service.
     *
     * @param deviceConfig the device configuration
     * @param config the service configuration.
     * @return an Optional containing a RunWith if enough information is present to create one. If the user information
     *         is not valid, an empty Optional is returned.
     */
    Optional<RunWith> generate(DeviceConfiguration deviceConfig, Topics config);

    /**
     * Validate the default device configuration for default RunWith values.
     * @param deviceConfig a config to validate.
     * @throws DeviceConfigurationException if the configuration is not valid.
     */
    void validateDefaultConfiguration(DeviceConfiguration deviceConfig) throws DeviceConfigurationException;
}
