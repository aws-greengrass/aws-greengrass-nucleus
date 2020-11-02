/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.amazon.aws.iot.greengrass.component.common.ComponentType;
import com.aws.greengrass.config.CaseInsensitiveString;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;
import com.aws.greengrass.util.Coerce;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.Kernel.SERVICE_TYPE_TOPIC_KEY;

/**
 * Helper function to get a logger with configurations separate from the root logger.
 */
public final class LogManagerHelper {
    public static final String NUCLEUS_CONFIG_LOGGING_TOPICS = "logging";
    static final String SERVICE_CONFIG_LOGGING_TOPICS = "ComponentLogging";
    private static final String LOG_FILE_EXTENSION = ".log";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES, true);
    private static final Logger logger = LogManager.getLogger(LogManagerHelper.class);

    private LogManagerHelper() {
    }

    /**
     * Handles subscribing and reconfiguring logger based on the correct topic.
     * @param kernel {@link Kernel}
     */
    public static void handleLoggingConfig(Kernel kernel) {
        kernel.getConfig()
                .lookup(SERVICES_NAMESPACE_TOPIC, getNucleusComponentName(kernel),
                        CONFIGURATION_CONFIG_KEY, NUCLEUS_CONFIG_LOGGING_TOPICS)
                //.dflt(LoggerConfiguration.builder().build())
                .subscribe((what, loggingParam) -> {
                    if (loggingParam == null) {
                        logger.atInfo().log("No logging configuration configured");
                        return;
                    }
                    try {
                        List<LoggerConfiguration> configuration = OBJECT_MAPPER.convertValue(loggingParam.toPOJO(),
                                new TypeReference<List<LoggerConfiguration>>() {
                                });
                        if (configuration == null) {
                            configuration =  new ArrayList<>();
                        }
                        if (configuration.isEmpty()) {
                            configuration.add(LoggerConfiguration.builder().build());
                        }
                        LogManager.reconfigureAllLoggers(configuration.get(0));
                    } catch (IllegalArgumentException e) {
                        logger.atError().kv("node", loggingParam.getFullName()).kv("value", loggingParam).setCause(e)
                                .log("Unable to parse logging configuration");
                    }
                });
    }

    /**
     * Get the Nucleus component name to lookup the configuration in the right place. If no component of type Nucleus
     * exists, create service config for the default Nucleus component.
     */
    private static String getNucleusComponentName(Kernel kernel) {
        Optional<CaseInsensitiveString> nucleusComponent =
                kernel.getConfig().lookupTopics(SERVICES_NAMESPACE_TOPIC).children.keySet().stream()
                        .filter(s -> ComponentType.NUCLEUS.name().equals(getComponentType(kernel, s.toString())))
                        .findAny();
        return nucleusComponent.map(CaseInsensitiveString::toString).orElse(DEFAULT_NUCLEUS_COMPONENT_NAME);
    }

    private static String getComponentType(Kernel kernel, String serviceName) {
        return Coerce.toString(kernel.getConfig().find(SERVICES_NAMESPACE_TOPIC, serviceName, SERVICE_TYPE_TOPIC_KEY));
    }


    /**
     * Get the logger for a particular component. The logs will be added to the a log file with the same name as the
     * component if the logs are configured to be written to the disk.
     *
     * @param service The green grass service instance to use to subscribe to logger config.
     * @return a logger with configuration to log to a los file with the same name.
     */
    public static Logger getComponentLogger(GreengrassService service) {
        service.getConfig().lookupTopics(SERVICE_CONFIG_LOGGING_TOPICS)
                .subscribe((why, newv) -> {
                    // GG_NEEDS_REVIEW: TODO: Reconfigure all service loggers using logging config in the service
                    // config.
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
}
