/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.nio.file.attribute.PosixFilePermission;
import java.util.HashSet;
import java.util.Set;

@Value
@Builder(toBuilder = true)
@AllArgsConstructor
public class FileSystemPermission {
    String ownerUser;
    String ownerGroup;
    boolean ownerRead;
    boolean ownerWrite;
    boolean ownerExecute;

    boolean groupRead;
    boolean groupWrite;
    boolean groupExecute;

    boolean otherRead;
    boolean otherWrite;
    boolean otherExecute;

    /**
     * Convert to a set of PosixFilePermissions for use with Files.setPosixFilePermissions.
     *
     * @return Set of permissions
     */
    public Set<PosixFilePermission> toPosixFilePermissions() {
        HashSet<PosixFilePermission> ret = new HashSet<>();

        if (ownerRead) {
            ret.add(PosixFilePermission.OWNER_READ);
        }
        if (ownerWrite) {
            ret.add(PosixFilePermission.OWNER_WRITE);
        }
        if (ownerExecute) {
            ret.add(PosixFilePermission.OWNER_EXECUTE);
        }
        if (groupRead) {
            ret.add(PosixFilePermission.GROUP_READ);
        }
        if (groupWrite) {
            ret.add(PosixFilePermission.GROUP_WRITE);
        }
        if (groupExecute) {
            ret.add(PosixFilePermission.GROUP_EXECUTE);
        }
        if (otherRead) {
            ret.add(PosixFilePermission.OTHERS_READ);
        }
        if (otherWrite) {
            ret.add(PosixFilePermission.OTHERS_WRITE);
        }
        if (otherExecute) {
            ret.add(PosixFilePermission.OTHERS_EXECUTE);
        }

        return ret;
    }

    public enum Option {
        /**
         * Apply permissions all the child directories and files.
         */
        Recurse,
        /**
         * Set owner.
         */
        SetOwner,
        /**
         * Set file mode (read/write/execute).
         */
        SetMode
    }
}
