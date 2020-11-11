/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.RunWithGenerator;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_USER;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;

/**
 * Generator for Unix. This checks for users to exist on the system and loads the primary group if one is not provided.
 * If they user does not exist (e.g. it is an arbitrary UID that does not map to a user on the box), then both a user
 * and  group must be provided.
 */
public class UnixRunWithGenerator implements RunWithGenerator {
    static final Logger logger = LogManager.getLogger(UnixRunWithGenerator.class);
    public static final String EVENT_TYPE = "generate-service-run-with-user-configuration";

    protected final UnixPlatform platform;

    /**
     * Create a new instance.
     *
     * @param platform the platform to use.
     */
    public UnixRunWithGenerator(UnixPlatform platform) {
        this.platform = platform;
    }

    private Pair<String, String> extractUserGroup(String user) {
        String group = null;
        if (user != null) {
            int colonIndex = user.indexOf(':');
            if (colonIndex > 0) {
                group = user.substring(colonIndex + 1);
                user = user.substring(0, colonIndex);
            }
        }
        return new Pair<>(user, group);
    }

    @SuppressWarnings({"PMD.NullAssignment","PMD.AvoidDeeplyNestedIfStmts"})
    @Override
    public Optional<RunWith> generate(DeviceConfiguration deviceConfig, Topics config) {
        // check component user, then default user, then nucleus user (if non root)
        String user = Coerce.toString(config.find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY));
        String group = null;

        boolean isDefault = false;

        if (Utils.isEmpty(user)) {
            logger.atDebug()
                    .setEventType(EVENT_TYPE)
                    .log("No component user, check default");

            user = Coerce.toString(deviceConfig.getRunWithDefaultPosixUser());
            isDefault = true;
        }

        // fallback to nucleus user if we aren't root
        if (Utils.isEmpty(user)) {
            logger.atDebug()
                    .setEventType(EVENT_TYPE)
                    .log("No default user, check current user");
            try {
                UnixUserAttributes attrs = platform.lookupCurrentUser();
                if (attrs.isSuperUser()) {
                    logger.atDebug()
                            .setEventType(EVENT_TYPE)
                            .log("Cannot fallback to super user");
                } else {
                    user = attrs.getPrincipalName();

                    if (!attrs.getPrimaryGID().isPresent()) {
                        // this should never happen - a user that is running has a group
                        return Optional.empty();
                    }
                    group = Long.toString(attrs.getPrimaryGID().get());
                    isDefault = false;
                }
            } catch (IOException e) {
                logger.atError()
                        .setEventType(EVENT_TYPE)
                        .setCause(e)
                        .log("Could not lookup current user and no default or override is present.");
                return Optional.empty();
            }
        } else {
            Pair<String, String> userGroup = extractUserGroup(user);
            user = userGroup.getLeft();
            group = userGroup.getRight();
        }

        if (Utils.isEmpty(user)) {
            logger.atDebug()
                    .setEventType(EVENT_TYPE)
                    .log("No user found");
            return Optional.empty();
        } else if (Utils.isEmpty(group)) {
            try {
                UnixUserAttributes attrs = platform.lookupUserByIdentifier(user);
                if (!attrs.getPrimaryGID().isPresent()) {
                    logger.atWarn()
                            .setEventType(EVENT_TYPE)
                            .kv("user", user)
                            .log("No primary group set for user.");
                    return Optional.empty();
                }
                group = Long.toString(attrs.getPrimaryGID().get());
            } catch (IOException e) {
                logger.atError()
                        .setEventType(EVENT_TYPE)
                        .setCause(e)
                        .kv("user", user)
                        .log("Could not lookup user.");
                return Optional.empty();
            }
        }
        return Optional.of(RunWith.builder().user(user).group(group).isDefault(isDefault)
                // shell cannot be changed from kernel default
                .shell(Coerce.toString(deviceConfig.getRunWithDefaultPosixShell())).build());
    }

    @Override
    public void validateDefaultConfiguration(DeviceConfiguration deviceConfig) throws DeviceConfigurationException {
        Pair<String, String> userGroup =
                extractUserGroup(Coerce.toString(deviceConfig.getRunWithDefaultPosixUser()));
        validateUserGroup(userGroup.getLeft(), userGroup.getRight());
    }

    @Override
    public void validateDefaultConfiguration(Map<String, Object> proposedDeviceConfig)
            throws DeviceConfigurationException {
        Pair<String, String> userGroup =
                extractUserGroup(Coerce.toString(proposedDeviceConfig.get(RUN_WITH_DEFAULT_POSIX_USER)));
        validateUserGroup(userGroup.getLeft(), userGroup.getRight());
    }

    private void validateUserGroup(String user, String group) throws DeviceConfigurationException {
        // user can be specified by itself if it is a valid user on the system (we can load the primary group)
        // user can be specified with a separate group
        // group cannot be specified without user

        if (Utils.isNotEmpty(user) && Utils.isEmpty(group)) {
            try {
                platform.lookupUserByIdentifier(user).getPrimaryGID().orElseThrow(
                        () -> new DeviceConfigurationException(String.format("%s requires a group when no primary "
                                + "group exists for the user: '%s'", RUN_WITH_DEFAULT_POSIX_USER, user)));
            } catch (IOException e) {
                throw new DeviceConfigurationException(String.format("Error while looking up primary group for %s. No"
                        + " group specified for the user: '%s'", user, RUN_WITH_DEFAULT_POSIX_USER), e);
            }
        } else if (Utils.isEmpty(user) && Utils.isNotEmpty(group)) {
            throw new DeviceConfigurationException(String.format("%s requires a user if a group is specified: '%s'",
                    RUN_WITH_DEFAULT_POSIX_USER, group));
        }
    }
}
