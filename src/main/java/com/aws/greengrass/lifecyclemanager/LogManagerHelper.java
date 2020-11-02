/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;

/**
 * Helper function to get a logger with configurations separate from the root logger.
 */
public final class LogManagerHelper {
    static final String KERNEL_CONFIG_LOGGING_TOPICS = "logging";
    static final String SERVICE_CONFIG_LOGGING_TOPICS = "ComponentLogging";
    private static final String LOG_FILE_EXTENSION = ".log";

    private LogManagerHelper() {
    }

    /**
     * Get the logger for a particular component. The logs will be added to the a log file with the same name as the
     * component if the logs are configured to be written to the disk.
     * @param service   The green grass service instance to use to subscribe to logger config.
     * @return  a logger with configuration to log to a los file with the same name.
     */
    public static Logger getComponentLogger(GreengrassService service) {
        // TODO: [P41214167]: Dynamically reconfigure service loggers
        service.getConfig().lookupTopics(SERVICE_CONFIG_LOGGING_TOPICS)
                .subscribe((why, newv) -> {
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
    private static Logger getComponentLogger(String name, String fileName) {
        return LogManager.getLogger(name, LoggerConfiguration.builder().fileName(fileName).build());
    }
}
