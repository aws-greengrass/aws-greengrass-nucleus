/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import lombok.Getter;

import java.util.Collections;
import java.util.Map;

/**
 * Decorate a command to run as another user or group.
 */
public abstract class UserDecorator implements CommandDecorator {
    @Getter
    protected String user;
    @Getter
    protected String group;
    @Getter
    protected Map<String, String> env = Collections.emptyMap();

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

    /**
     * Set environment variables to preserve when switching users.
     *
     * @param env map of env var names to values; only the keys are used by the decorator.
     * @return this.
     */
    public UserDecorator withEnv(Map<String, String> env) {
        this.env = env == null ? Collections.emptyMap() : env;
        return this;
    }
}
