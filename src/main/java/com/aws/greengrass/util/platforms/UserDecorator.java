/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import lombok.Getter;

/**
 * Decorate a command to run as another user or group.
 */
public abstract class UserDecorator implements CommandDecorator {
    @Getter
    protected String user;
    @Getter
    protected String group;

    /**
     * Set the user to run with.
     * @param user a user identifier.
     * @return this.
     */
    public UserDecorator withUser(String user) {
        this.user = user;
        return this;
    }

    /**
     * Set the group to run with.
     * @param group a group identifier.
     * @return this.
     */
    public UserDecorator withGroup(String group) {
        this.group = group;
        return this;
    }
}
