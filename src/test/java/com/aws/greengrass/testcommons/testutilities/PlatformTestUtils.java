/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.unix.UnixPlatformTestUtils;
import com.aws.greengrass.testcommons.testutilities.windows.WindowsPlatformTestUtils;
import com.aws.greengrass.util.FileSystemPermission;

import java.nio.file.Path;

public abstract class PlatformTestUtils {

    private static PlatformTestUtils INSTANCE;

    private static final Logger logger = LogManager.getLogger(PlatformTestUtils.class);

    public static synchronized PlatformTestUtils getInstance() {
        if (INSTANCE != null) {
            return INSTANCE;
        }

        if (PlatformResolver.isWindows) {
            INSTANCE = new WindowsPlatformTestUtils();
        } else {
            INSTANCE = new UnixPlatformTestUtils();
        }

        logger.atInfo().log("Getting platform test utils instance {}.", INSTANCE.getClass().getName());
        return INSTANCE;
    }

    public abstract boolean hasPermission(FileSystemPermission expected, Path path);
}
