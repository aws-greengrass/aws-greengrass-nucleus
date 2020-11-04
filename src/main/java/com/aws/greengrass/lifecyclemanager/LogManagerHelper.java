/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import org.slf4j.event.Level;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;

/**
 * Helper function to get a logger with configurations separate from the root logger.
 */
public final class LogManagerHelper {
    public static final String NUCLEUS_CONFIG_LOGGING_TOPICS = "logging";
    static final String SERVICE_CONFIG_LOGGING_TOPICS = "ComponentLogging";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final Logger logger = LogManager.getLogger(LogManagerHelper.class);

    private LogManagerHelper() {
    }

    /**
     * Handles subscribing and reconfiguring logger based on the correct topic.
     * @param kernel {@link Kernel}
     */
    public static void handleLoggingConfig(Kernel kernel) {
        Pair<String, Boolean> nucleusComponentNamePair = DeviceConfiguration.getNucleusComponentName(kernel);
        kernel.getConfig()
                .lookupTopics(SERVICES_NAMESPACE_TOPIC, nucleusComponentNamePair.getLeft(),
                        CONFIGURATION_CONFIG_KEY, NUCLEUS_CONFIG_LOGGING_TOPICS)
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

    private static void handleLoggingConfigurationChanges(WhatHappened what, Node loggingParam) {
        if (loggingParam == null) {
            logger.atInfo().log("No logging configuration configured");
            return;
        }
        LoggerConfiguration configuration2 = LoggerConfiguration.builder().build();
        if (WhatHappened.childChanged.equals(what)) {
            if ("level".equals(loggingParam.getFullName())) {
                configuration2.setLevel(Level.valueOf(Coerce.toString(loggingParam.toPOJO())));
            }
            if ("fileSizeKB".equals(loggingParam.getFullName())) {
                configuration2.setFileSizeKB(Coerce.toLong(loggingParam.toPOJO()));
            }
            if ("totalLogsSizeKB".equals(loggingParam.getFullName())) {
                configuration2.setTotalLogsSizeKB(Coerce.toLong(loggingParam.toPOJO()));
            }
            if ("format".equals(loggingParam.getFullName())) {
                configuration2.setFormat(LogFormat.valueOf(Coerce.toString(loggingParam.toPOJO())));
            }
            if ("outputDirectory".equals(loggingParam.getFullName())) {
                configuration2.setOutputDirectory(Coerce.toString(loggingParam.toPOJO()));
            }
            if ("outputType".equals(loggingParam.getFullName())) {
                configuration2.setOutputType(LogStore.valueOf(Coerce.toString(loggingParam.toPOJO())));
            }
        }
        LogManager.reconfigureAllLoggers(configuration2);
    }
}
