/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.nio.file.Path;

public final class Permissions {
    private static final Platform platform = Platform.getInstance();
    private static final FileSystemPermission OWNER_RWX_ONLY =
            new FileSystemPermission(null, null, true, true, true, false, false, false, false, false, false);
    private static final FileSystemPermission OWNER_RWX_EVERYONE_RX =
            new FileSystemPermission(null, null, true, true, true, true, false, true, true, false, true);

    private Permissions() {
    }

    public static void setArtifactPermission(Path p) throws IOException {
    }

    public static void setComponentStorePermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    public static void setArtifactStorePermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    public static void setRecipeStorePermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setWorkPathPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    public static void setServiceWorkPathPermission(Path p) throws IOException {
    }

    public static void setRootPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    public static void setKernelAltsPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setDeploymentPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setConfigPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setPluginPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setTelemetryPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setLoggerPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }
}
