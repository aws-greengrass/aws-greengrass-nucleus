/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

/**
 * Decorate a command to run as another user or group.
 */
public interface UserDecorator extends CommandDecorator {
    /**
     * Set the user to run with.
     * @param user a user identifier.
     * @return this.
     */
    UserDecorator withUser(String user);

    /**
     * Set the group to run with.
     * @param group a group identifier.
     * @return this.
     */
    UserDecorator withGroup(String group);
}
