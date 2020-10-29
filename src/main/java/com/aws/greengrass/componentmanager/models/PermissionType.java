/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.models;

/**
 * Permission attribute to set. In Linux this corresponds to setting the User or Other bits of the standard POSIX
 * file permissions. In Windows this would correspond with modifying the ACL for owner and "Everyone" groups.
 */
public enum PermissionType {
    /**
     * No permissions.
     */
    NONE,
    /**
     * Owner of file has permission.
     */
    OWNER,
    /**
     * All users have permission.
     */
    ALL;

    public static PermissionType fromString(String s) {
        return PermissionType.valueOf(s.toUpperCase());
    }

}
