/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;

/**
 * Helper function to get a logger with configurations separate from the root logger.
 */
public class LogManagerHelper {
    private static final String LOG_FILE_EXTENSION = ".log";
    private final Kernel kernel;

    public LogManagerHelper(Kernel kernel) {
        this.kernel = kernel;
        // TODO: Subscribe to logging configuration in the kernel config and reconfigure all the loggers.
    }

    /**
     * Get the logger for a particular component. The logs will be added to the a log file with the same name as the
     * component if the logs are configured to be written to the disk.
     * @param name  The name of the component
     * @return  a logger with configuration to log to a los file with the same name.
     */
    public com.aws.greengrass.logging.api.Logger getComponentLogger(String name) {
        return getComponentLogger(name, name + LOG_FILE_EXTENSION);
    }
    /**
     * Get the logger for a particular component. The logs will be added to the log file name provided in the method
     * signature if the logs are configured to be written to the disk.
     * @param name      The name of the component
     * @param fileName  The name of the log file.
     * @return a logger with configuration to log to a los file with the same name.
     */
    public com.aws.greengrass.logging.api.Logger getComponentLogger(String name, String fileName) {
        return LogManager.getLogger(name, LoggerConfiguration.builder().fileName(fileName).build());
    }
}
