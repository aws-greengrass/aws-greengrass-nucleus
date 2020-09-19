/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;

import java.io.IOException;

public abstract class Platform {
    protected static final Logger logger = LogManager.getLogger(Platform.class);
    private static Platform INSTANCE;

    /**
     * Get the appropriate instance of Platform for the current platform.
     *
     * @return Platform
     */
    public static synchronized Platform getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        if (Exec.isWindows) {
            INSTANCE = new WindowsPlatform();
        } else if (PlatformResolver.RANKS.get().containsKey("qnx")) {
            INSTANCE = new QNXPlatform();
        } else {
            INSTANCE = new UnixPlatform();
        }

        return INSTANCE;
    }

    public abstract void killProcessAndChildren(Process process, boolean force)
            throws IOException, InterruptedException;

    public abstract String[] getShellForCommand(String command);

    public abstract int exitCodeWhenCommandDoesNotExist();
}
