/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.FileSystemPermission.Option;
import com.aws.greengrass.util.platforms.unix.DarwinPlatform;
import com.aws.greengrass.util.platforms.unix.QNXPlatform;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static com.aws.greengrass.config.PlatformResolver.OS_DARWIN;

public abstract class Platform implements UserPlatform {
    public static final Logger logger = LogManager.getLogger(Platform.class);

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

        if (PlatformResolver.isWindows) {
            INSTANCE = new WindowsPlatform();
        } else if (OS_DARWIN.equals(PlatformResolver.getOSInfo())) {
            INSTANCE = new DarwinPlatform();
        } else if (System.getProperty("os.name").toLowerCase().contains("qnx")) {
            INSTANCE = new QNXPlatform();
        } else {
            INSTANCE = new UnixPlatform();
        }

        logger.atInfo().log("Getting platform instance {}.", INSTANCE.getClass().getName());

        return INSTANCE;
    }

    public abstract Set<Integer> killProcessAndChildren(Process process, boolean force, Set<Integer> additionalPids,
                                                        UserDecorator decorator)
            throws IOException, InterruptedException;

    public abstract ShellDecorator getShellDecorator();

    public abstract int exitCodeWhenCommandDoesNotExist();

    public abstract UserDecorator getUserDecorator();

    public abstract String getPrivilegedGroup();

    public abstract String getPrivilegedUser();

    public abstract RunWithGenerator getRunWithGenerator();

    public abstract void createUser(String user) throws IOException;

    public abstract void createGroup(String group) throws IOException;

    public abstract void addUserToGroup(String user, String group) throws IOException;

    /**
     * Set permissions on a path.
     *
     * @param permission permissions to set
     * @param path path to apply to
     * @param options options for how to apply the permission to the path - if none, then the mode is set
     * @throws IOException if any exception occurs while changing permissions
     */
    public void setPermissions(FileSystemPermission permission, Path path,
                                        Option... options) throws IOException {
        // convert to set for easier checking of set options
        EnumSet<Option> set = options.length == 0 ? EnumSet.of(Option.SetMode) :
                EnumSet.copyOf(Arrays.asList(options));
        setPermissions(permission, path, set);
    }

    /**
     * Set permissions on a path. This changes the mode and owner.
     *
     * @param permission permissions to set
     * @param path path to apply to
     * @throws IOException if any exception occurs while changing permissions
     */
    public void setPermissions(FileSystemPermission permission, Path path) throws IOException {
        setPermissions(permission, path, EnumSet.of(Option.SetMode, Option.SetOwner));
    }

    /**
     * Set permission on a path.
     *
     * @param permission permissions to set
     * @param path path to apply to
     * @param options options for how to apply the permission to the path
     * @throws IOException if any exception occurs while changing permissions
     */
    protected abstract void setPermissions(FileSystemPermission permission, Path path, EnumSet<Option> options)
            throws IOException;

    public abstract String prepareDomainSocketFilepath();

    public abstract String prepareDomainSocketFilepathForComponent();

    public abstract String prepareDomainSocketFilepathForRpcServer();

    public abstract void setIpcBackingFilePermissions();

    public abstract void cleanupIpcBackingFile();
}
