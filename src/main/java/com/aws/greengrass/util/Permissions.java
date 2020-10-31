/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public final class Permissions {
    private static final Platform platform = Platform.getInstance();
    private static final FileSystemPermission OWNER_RWX_ONLY =  FileSystemPermission.builder()
            .ownerRead(true).ownerWrite(true).ownerExecute(true).build();
    private static final FileSystemPermission OWNER_RWX_EVERYONE_RX = FileSystemPermission.builder()
            .ownerRead(true).ownerWrite(true).ownerExecute(true)
            .groupRead(true).groupExecute(true)
            .otherRead(true).otherExecute(true)
            .build();
    private static final FileSystemPermission OWNER_R_ONLY =
            FileSystemPermission.builder().ownerRead(true).build();

    private Permissions() {
    }

    /**
     * Set default permissions on an artifact.
     *
     * @param p the artifact path.
     * @throws IOException if an error occurs.
     */
    public static void setArtifactPermission(Path p) throws IOException {
        if (p == null || !Files.exists(p)) {
            return;
        }
        // default artifact permissions - readable by owner but everyone can access dirs
        if (Files.isDirectory(p)) {
            platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
            for (Iterator<Path> it = Files.list(p).iterator(); it.hasNext(); ) {
                setArtifactPermission(it.next());
            }
        } else {
            platform.setPermissions(OWNER_R_ONLY, p);
        }
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
    }

    public static void setCliIpcInfoPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    /**
     * Set permissions on the IPC socket path.
     *
     * @param p path to socket.
     * @throws IOException if permissions could not be set.
     */
    public static void setIpcSocketPermission(Path p) throws IOException {
        // note this uses File#set methods as using posix permissions fails.
        boolean succeeded = p.toFile().setReadable(true, false)
                && p.toFile().setWritable(true, false)
                && p.toFile().setExecutable(false, false);
        if (!succeeded) {
            throw new IOException("Could not set permissions on " + p.toString());
        }
    }
}
