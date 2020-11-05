/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;
import com.aws.greengrass.util.Coerce;
import org.slf4j.event.Level;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

/**
 * Helper function to get a logger with configurations separate from the root logger.
 */
public final class LogManagerHelper {
    static final String SERVICE_CONFIG_LOGGING_TOPICS = "ComponentLogging";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static Topics loggingTopics;
    private static Kernel kernel;
    private static LoggerConfiguration currentConfiguration;
    private static final Logger logger = LogManager.getLogger(LogManagerHelper.class);

    private LogManagerHelper() {
    }

    /**
     * Handles subscribing and reconfiguring logger based on the correct topic.
     * @param kernel {@link Kernel}
     */
    public static void handleLoggingConfig(Kernel kernel) {
        LogManagerHelper.kernel = kernel;
        loggingTopics = DeviceConfiguration.getLoggingConfigurationTopic(kernel)
                .subscribe(LogManagerHelper::handleLoggingConfigurationChanges);
    }

    /**
     * Get the logger for a particular component. The logs will be added to the a log file with the same name as the
     * component if the logs are configured to be written to the disk.
     *
     * @param service The green grass service instance to use to subscribe to logger config.
     * @return a logger with configuration to log to a los file with the same name.
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
     *
     * @param name     The name of the component
     * @param fileName The name of the log file.
     * @return a logger with configuration to log to a los file with the same name.
     */
    private static Logger getComponentLogger(String name, String fileName) {
        return LogManager.getLogger(name, LoggerConfiguration.builder().fileName(fileName).build());
    }

    @SuppressWarnings("PMD.UselessParentheses")
    static synchronized void handleLoggingConfigurationChanges(WhatHappened what, Node loggingParam) {
        if (loggingTopics == null) {
            return;
        }
        LoggerConfiguration configuration;
        try {
            configuration = fromPojo(loggingTopics.toPOJO());
        } catch (IllegalArgumentException e) {
            logger.atError().kv("logging-config", loggingTopics).cause(e).log("Unable to parse logging config.");
            return;
        }
        if (currentConfiguration == null || !currentConfiguration.equals(configuration)) {
            if (configuration.getOutputDirectory() != null
                    && (currentConfiguration == null || !Objects.equals(currentConfiguration.getOutputDirectory(),
                    configuration.getOutputDirectory()))) {
                try {
                    LogManagerHelper.kernel.getNucleusPaths()
                            .setLoggerPath(Paths.get(configuration.getOutputDirectory()));
                } catch (IOException e) {
                    logger.atError().cause(e).log("Unable to initialize logger output directory path");
                }
            }
            currentConfiguration = configuration;
            LogManager.reconfigureAllLoggers(configuration);
        }
    }

    /**
     * Get the logger configuration from POJO.
     * @param pojoMap   The map containing logger configuration.
     * @return  the logger configuration.
     * @throws IllegalArgumentException if the POJO map has an invalid argument.
     */
    private static LoggerConfiguration fromPojo(Map<String, Object> pojoMap) {
        LoggerConfiguration configuration = LoggerConfiguration.builder().build();
        pojoMap.forEach((s, o) -> {
            switch (s) {
                case "level":
                    configuration.setLevel(Level.valueOf(Coerce.toString(o)));
                    break;
                case "fileSizeKB":
                    configuration.setFileSizeKB(Coerce.toLong(o));
                    break;
                case "totalLogsSizeKB":
                    configuration.setTotalLogsSizeKB(Coerce.toLong(o));
                    break;
                case "format":
                    configuration.setFormat(LogFormat.valueOf(Coerce.toString(o)));
                    break;
                case "outputDirectory":
                    configuration.setOutputDirectory(Coerce.toString(o));
                    break;
                case "outputType":
                    configuration.setOutputType(LogStore.valueOf(Coerce.toString(o)));
                    break;
                default:
                    throw new IllegalArgumentException("Unexpected value: " + s);
            }
        });
        return configuration;
    }

}
