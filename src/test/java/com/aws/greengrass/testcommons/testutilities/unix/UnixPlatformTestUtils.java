/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities.unix;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.PlatformTestUtils;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.platforms.unix.UnixPlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

public class UnixPlatformTestUtils extends PlatformTestUtils {

    private static final Logger logger = LogManager.getLogger(UnixPlatformTestUtils.class);

    @Override
    public boolean hasPermission(FileSystemPermission expected, Path path) {
        PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);

        if (view == null) {
            logger.atError().log("Platform does not have PosixFileAttributeView");
            return false;
        }

        try {
            Set<PosixFilePermission> perms = UnixPlatform.posixFilePermissions(expected);
            Set<PosixFilePermission> actual = view.readAttributes().permissions();
            logger.atTrace().log("posix permissions are {} for path {}", PosixFilePermissions.toString(actual), path);
            return actual.containsAll(perms) && perms.containsAll(actual);
        } catch (IOException e) {
            logger.atError().cause(e).log("encountered IOException when checking file system permission");
            return false;
        }
    }
}
