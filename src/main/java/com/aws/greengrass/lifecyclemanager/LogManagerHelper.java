/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

/**
 * Helper function to get a logger with configurations separate from the root logger.
 */
public class LogManagerHelper {
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final String KERNEL_CONFIG_PARAMETER_TOPIC = "rootLoggerConfig";
    private static final String SERVICE_CONFIG_PARAMETER_TOPIC = "ComponentLoggerConfig";
    private final Kernel kernel;

    public LogManagerHelper(Kernel kernel) {
        this.kernel = kernel;
        this.kernel.getConfig().lookup(PARAMETERS_CONFIG_KEY, KERNEL_CONFIG_PARAMETER_TOPIC)
                .subscribe((why, newv) -> {
                    // TODO: Reconfigure all loggers using logging configuration in the kernel config.
                });
    }

    /**
     * Get the logger for a particular component. The logs will be added to the a log file with the same name as the
     * component if the logs are configured to be written to the disk.
     * @param service   The green grass service instance to use to subscribe to logger config.
     * @return  a logger with configuration to log to a los file with the same name.
     */
    public com.aws.greengrass.logging.api.Logger getComponentLogger(GreengrassService service) {
        service.getConfig().lookup(PARAMETERS_CONFIG_KEY, SERVICE_CONFIG_PARAMETER_TOPIC)
                .subscribe((why, newv) -> {
                    // TODO: Reconfigure all service loggers using logging configuration in the service config.
                });

        return getComponentLogger(service.getServiceName(), service.getServiceName() + LOG_FILE_EXTENSION);
    }

    /**
     * Get the logger for a particular component. The logs will be added to the log file name provided in the method
     * signature if the logs are configured to be written to the disk.
     * @param name      The name of the component
     * @param fileName  The name of the log file.
     * @return a logger with configuration to log to a los file with the same name.
     */
    private com.aws.greengrass.logging.api.Logger getComponentLogger(String name, String fileName) {
        return LogManager.getLogger(name, LoggerConfiguration.builder().fileName(fileName).build());
    }
}
