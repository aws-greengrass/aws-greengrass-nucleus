/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.platforms.RunWithGenerator;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Generator for Unix. This checks for users to exist on the system and loads the primary group if one is not provided.
 * If the user does not exist (e.g. it is an arbitrary UID that does not map to a user on the box), then both a user
 * and  group must be provided.
 */
public class AndroidRunWithGenerator implements RunWithGenerator {
    static final Logger logger = LogManager.getLogger(AndroidRunWithGenerator.class);
    public static final String EVENT_TYPE = "generate-service-run-with-user-configuration";

    protected final AndroidPlatform platform;

    /**
     * Create a new instance.
     *
     * @param platform the platform to use.
     */
    public AndroidRunWithGenerator(AndroidPlatform platform) {
        this.platform = platform;
    }

    @SuppressWarnings({"PMD.NullAssignment","PMD.AvoidDeeplyNestedIfStmts"})
    @Override
    public Optional<RunWith> generate(DeviceConfiguration deviceConfig, Topics config) {
        // check component user, then default user, then nucleus user (if non root)
        String user;
        String group;

        try {
            AndroidUserAttributes attrs = platform.lookupCurrentUser();
            user = attrs.getPrincipalName();

            if (!attrs.getPrimaryGID().isPresent()) {
                // this should never happen - a user that is running has a group
                return Optional.empty();
            }
            group = Long.toString(attrs.getPrimaryGID().get());
        } catch (IOException e) {
            logger.atError()
                    .setEventType(EVENT_TYPE)
                    .setCause(e)
                    .log("Could not lookup current user");
            return Optional.empty();
        }

        return Optional.of(RunWith.builder().user(user).group(group).isDefault(true)
                // shell cannot be changed from kernel default
                .shell(Coerce.toString(deviceConfig.getRunWithDefaultPosixShell())).build());
    }

    @Override
    public void validateDefaultConfiguration(DeviceConfiguration deviceConfig) throws DeviceConfigurationException {
        logger.atTrace()
                .setEventType(EVENT_TYPE)
                .log("User/group configuration is not supported on Android");
    }

    @Override
    public void validateDefaultConfiguration(Map<String, Object> proposedDeviceConfig)
            throws DeviceConfigurationException {
        logger.atTrace()
                .setEventType(EVENT_TYPE)
                .log("User/group configuration is not supported on Android");
    }
}
