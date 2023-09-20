/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.stream.Stream;

import static com.aws.greengrass.util.FileSystemPermission.Option.SetMode;

public final class Permissions {
    static Platform platform = Platform.getInstance();

    static final FileSystemPermission OWNER_RWX_ONLY =  FileSystemPermission.builder()
            .ownerRead(true).ownerWrite(true).ownerExecute(true).build();
    static final FileSystemPermission OWNER_RW_ONLY =  FileSystemPermission.builder()
            .ownerRead(true).ownerWrite(true).build();
    public static final FileSystemPermission OWNER_RWX_EVERYONE_RX = FileSystemPermission.builder()
            .ownerRead(true).ownerWrite(true).ownerExecute(true)
            .groupRead(true).groupExecute(true)
            .otherRead(true).otherExecute(true)
            .build();

    private Permissions() {
    }

    /**
     * Set default permissions on an artifact.
     *
     * @param p the artifact path.
     * @param permission the permission to apply.
     * @throws IOException if an error occurs.
     */
    @SuppressWarnings("PMD.ForLoopCanBeForeach")
    public static void setArtifactPermission(Path p, FileSystemPermission permission) throws IOException {
        if (p == null || !Files.exists(p)) {
            return;
        }
        // default artifact permissions - readable by owner but everyone can access dirs
        if (Files.isDirectory(p)) {
            platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
            try (Stream<Path> files = Files.list(p)) {
                for (Iterator<Path> it = files.iterator(); it.hasNext(); ) {
                    setArtifactPermission(it.next(), permission);
                }
            }
        } else {
            if (!PlatformResolver.isWindows && !platform.lookupCurrentUser().isSuperUser()
                    && !permission.isOwnerWrite()) {
                // If not running on windows, and not running as a super user, ownership cannot be changed and users
                // can override permissions outside of Greengrass. Set write permission so the file can be deleted on
                // cleanup of artifacts.
                permission = permission.toBuilder().ownerWrite(true).build();
            }
            // don't reset the owner when setting permissions
            platform.setPermissions(permission, p, SetMode);
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

    /**
     * Set permission for service path under the "work" path.
     *
     * @param p the path to a service work directory
     * @throws IOException if permissions cannot be set.
     */
    public static void setServiceWorkPathPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_ONLY, p);
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
        platform.setPermissions(OWNER_RWX_ONLY, p);
    }

    public static void setCliIpcInfoPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    public static void setBinPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RWX_EVERYONE_RX, p);
    }

    public static void setPrivateKeyPermission(Path p) throws IOException {
        platform.setPermissions(OWNER_RW_ONLY, p);
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
