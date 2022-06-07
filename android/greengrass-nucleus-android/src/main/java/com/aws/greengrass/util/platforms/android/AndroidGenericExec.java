/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.android;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;

import java.io.File;
import java.util.HashMap;

public abstract class AndroidGenericExec extends Exec {

    private static final Logger staticLogger = LogManager.getLogger(AndroidGenericExec.class);

    AndroidGenericExec() {
        super();
        environment = new HashMap<>(defaultEnvironment);
    }

    @Override
    public Exec cd(File f) {
        staticLogger.atWarn("Setting of working directory is not possible on Android. Skipped");
        return this;
    }

    @Override
    public File cwd() {
        staticLogger.atWarn("Attempt to determine component's working directory - not relevant for Android");
        return null;
    }

    @Override
    public Exec withUser(String user) {
        staticLogger.atWarn("Execution with different user is not supported on Android");
        return this;
    }

    @Override
    public Exec withGroup(String group) {
        staticLogger.atWarn("Execution with specified group is not supported on Android");
        return this;
    }
}
