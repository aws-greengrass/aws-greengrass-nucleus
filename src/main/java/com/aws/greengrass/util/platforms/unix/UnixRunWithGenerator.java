/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.lifecyclemanager.RunWith;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;
import com.aws.greengrass.util.platforms.RunWithGenerator;

import java.io.IOException;
import java.util.Optional;

import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_GROUP;
import static com.aws.greengrass.deployment.DeviceConfiguration.RUN_WITH_DEFAULT_POSIX_USER;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_GROUP_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.POSIX_USER_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.RUN_WITH_NAMESPACE_TOPIC;

/**
 * Generator for Unix. This checks for users to exist on the system and loads the primary group if one is not provided.
 * If they user does not exist (e.g. it is an arbitrary UID that does not map to a user on the box), then both a user
 * and  group must be provided.
 */
public class UnixRunWithGenerator implements RunWithGenerator {

    private final UnixPlatform platform;

    /**
     * Create a new instance.
     *
     * @param platform the platform to use.
     */
    public UnixRunWithGenerator(UnixPlatform platform) {
        this.platform = platform;
    }

    @SuppressWarnings({"PMD.NullAssignment","PMD.AvoidDeeplyNestedIfStmts"})
    @Override
    public Optional<RunWith> generate(DeviceConfiguration deviceConfig, Topics config) {
        // check component user, then default user, then nucleus user (if non root)
        String user = Coerce.toString(config.find(RUN_WITH_NAMESPACE_TOPIC, POSIX_USER_KEY));
        String group = Coerce.toString(config.find(RUN_WITH_NAMESPACE_TOPIC, POSIX_GROUP_KEY));
        boolean isDefault = false;

        if (Utils.isEmpty(user)) {
            user = Coerce.toString(deviceConfig.getRunWithDefaultPosixUser());
            group = Coerce.toString(deviceConfig.getRunWithDefaultPosixGroup());
            isDefault = true;
        }

        try {
            // fallback to nucleus user if we aren't root
            if (Utils.isEmpty(user)) {
                UnixUserAttributes attrs = platform.lookupCurrentUser();
                if (!attrs.isSuperUser()) {
                    user = attrs.getPrincipalName();

                    if (!attrs.getPrimaryGID().isPresent()) {
                        // this should never happen - a user that is running has a group
                        return Optional.empty();
                    }
                    group = Integer.toString(attrs.getPrimaryGID().get());
                    isDefault = false;
                }
            }

            if (Utils.isEmpty(user)) {
                return Optional.empty();
            } else if (Utils.isEmpty(group)) {
                UnixUserAttributes attrs = platform.lookupUserByIdentifier(user);
                if (!attrs.getPrimaryGID().isPresent()) {
                    return Optional.empty();
                }
                group = Integer.toString(attrs.getPrimaryGID().get());
            }
            return Optional.of(RunWith.builder().user(user).group(group).isDefault(isDefault)
                    // shell cannot be changed from kernel default
                    .shell(Coerce.toString(deviceConfig.getRunWithDefaultPosixShell())).build());
        } catch (IOException e) {

            return Optional.empty();
        }
    }

    @Override
    public void validateDefaultConfiguration(DeviceConfiguration deviceConfig) throws DeviceConfigurationException {
        // user can be specified by itself if it is a valid user on the system (we can load the primary group)
        // user can be specified with a separate group
        // group cannot be specified without user
        String user = Coerce.toString(deviceConfig.getRunWithDefaultPosixUser());
        String group = Coerce.toString(deviceConfig.getRunWithDefaultPosixGroup());

        if (!Utils.isEmpty(user) && Utils.isEmpty(group)) {
            try {
                platform.lookupUserByIdentifier(user).getPrimaryGID().orElseThrow(
                        () -> new DeviceConfigurationException(RUN_WITH_DEFAULT_POSIX_GROUP + " cannot be empty"));
            } catch (IOException e) {
                throw new DeviceConfigurationException(
                        "Error while looking up primary group for " + user + ". " + RUN_WITH_DEFAULT_POSIX_GROUP
                                + " is empty", e);
            }
        } else if (Utils.isEmpty(user) && !Utils.isEmpty(group)) {
            throw new DeviceConfigurationException(
                    RUN_WITH_DEFAULT_POSIX_USER + " cannot be empty if " + RUN_WITH_DEFAULT_POSIX_GROUP
                            + " is provided");
        }
    }
}
