/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.testcommons.testutilities.windows;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.PlatformTestUtils;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.platforms.windows.WindowsPlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.List;

public class WindowsPlatformTestUtils extends PlatformTestUtils {

    private static final Logger logger = LogManager.getLogger(WindowsPlatformTestUtils.class);

    @Override
    public boolean hasPermission(FileSystemPermission expected, Path path) {
        AclFileAttributeView view =
                Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);

        if (view == null) {
            logger.atError().log("Platform does not have AclFileAttributeView");
            return false;
        }

        try {
            List<AclEntry> perms = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(expected, path);
            List<AclEntry> actual = view.getAcl();
            logger.atTrace().log("Acl entries are {} for path {}", actual.toString(), path);
            return actual.containsAll(perms) && perms.containsAll(actual);
        } catch (IOException e) {
            logger.atError().cause(e).log("encountered IOException when checking file system permission");
            return false;
        }
    }
}
