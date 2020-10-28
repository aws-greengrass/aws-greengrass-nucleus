/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Exec;
import com.aws.greengrass.util.FileSystemPermission;

import java.io.IOException;
import java.nio.file.Path;

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

    public abstract ShellDecorator getShellDecorator();

    public abstract int exitCodeWhenCommandDoesNotExist();

    public abstract UserDecorator getUserDecorator();

    public abstract String getPrivilegedGroup();

    public abstract String getPrivilegedUser();

    /**
     * Set permissions on a path.
     *
     * @param permission permissions to set
     * @param path path to apply to
     * @throws IOException if any exception occurs while changing permissions
     */
    public abstract void setPermissions(FileSystemPermission permission, Path path) throws IOException;

    public abstract Group getGroup(String group) throws IOException;

    public abstract int getEffectiveUID() throws IOException, InterruptedException;
}
