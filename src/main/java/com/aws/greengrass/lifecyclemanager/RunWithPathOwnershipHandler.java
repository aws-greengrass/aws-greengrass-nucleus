/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import javax.inject.Inject;

import static com.aws.greengrass.util.FileSystemPermission.Option.IgnorePermission;
import static com.aws.greengrass.util.FileSystemPermission.Option.Recurse;

/**
 * Update ownership based on service RunWith information.
 */
public class RunWithPathOwnershipHandler {

    final NucleusPaths nucleusPaths;

    final Platform platform;

    /**
     * Construct a new handler.
     *
     * @param paths paths in the nucleus.
     */
    @Inject
    public RunWithPathOwnershipHandler(NucleusPaths paths) {
        this(paths, Platform.getInstance());
    }

    /**
     * Construct a new handler.
     *
     * @param paths paths in the nucleus.
     * @param platform the platform instance.
     */
    public RunWithPathOwnershipHandler(NucleusPaths paths, Platform platform) {
        this.nucleusPaths = paths;
        this.platform = platform;
    }

    /**
     * Update the owner of the artifacts and work path in the component on the local filesystem. The user and group of
     * from the RunWith parameter are used.
     *
     * @param id      the component to update.
     * @param runWith the user/group that should own the files.
     * @throws IOException if an error occurs while updating. This can occur if the user running the kernel does not
     *                     have the correct permissions or capabilities to change file ownership to another user.
     */
    public void updateOwner(ComponentIdentifier id, RunWith runWith) throws IOException {
        Path artifacts = nucleusPaths.artifactPath(id);
        Path unarchived = nucleusPaths.unarchiveArtifactPath(id);
        Path workPath = nucleusPaths.workPath(id.getName());

        FileSystemPermission permission = FileSystemPermission.builder()
                .ownerUser(runWith.getUser())
                .ownerGroup(runWith.getGroup())
                .build();

        // change ownership of files within the artifact dirs, but don't change the artifact dir itself as that would
        // make it writable to the user
        setPermissions(artifacts, permission, false);
        setPermissions(unarchived, permission, false);

        // change ownership of work dir
        setPermissions(workPath, permission, true);
    }

    void setPermissions(Path p, FileSystemPermission permission,  boolean applyToRoot) throws IOException {
        if (Files.notExists(p)) {
            return;
        }
        if (applyToRoot) {
            platform.setPermissions(permission, p, Recurse, IgnorePermission);
        } else {
            for (Iterator<Path> it = Files.list(p).iterator(); it.hasNext(); ) {
                platform.setPermissions(permission, it.next(), Recurse, IgnorePermission);
            }
        }
    }

}
