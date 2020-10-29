/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

import com.aws.greengrass.util.FileSystemPermission;
import lombok.Builder;
import lombok.NonNull;
import lombok.Value;


/**
 * Permission settings for component artifacts. Read and Execute permissions can be set.
 * By default, the read permission is set to allow only the owner of the artifact on the disk and the execute permission
 * is set to NONE.
 */
@Value
@Builder
public class Permission {
        @NonNull
        @Builder.Default
        PermissionType read = PermissionType.OWNER;
        @NonNull
        @Builder.Default
        PermissionType execute = PermissionType.NONE;

        /**
         * Convert to file system permission.
         *
         * @return a permission.
         */
        public FileSystemPermission toFileSystemPermission() {
                return FileSystemPermission.builder()
                        .ownerRead(true) // we always want owner to read
                        .ownerExecute(execute == PermissionType.ALL || execute == PermissionType.OWNER)
                        .groupRead(read == PermissionType.ALL)
                        .groupExecute(execute == PermissionType.ALL)
                        .otherRead(read == PermissionType.ALL)
                        .otherExecute(execute == PermissionType.ALL)
                        .build();
        }
}
