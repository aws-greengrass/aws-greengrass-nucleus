/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Value;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
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

    /**
     * Convert to a list of Acl entries for use with AclFileAttributeView.setAcl.
     *
     * @param path path to apply to
     * @return List of Acl entries
     * @throws IOException if any exception occurs while converting to Acl
     */
    public List<AclEntry> toAclEntries(Path path) throws IOException {
        // These sets of permissions are reverse engineered by setting the permission on a file using Windows File
        // Explorer. Then use AclFileAttributeView.getAcl to examine what were set.
        final Set<AclEntryPermission> readPerms = new HashSet<>(Arrays.asList(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.SYNCHRONIZE));
        final Set<AclEntryPermission> writePerms = new HashSet<>(Arrays.asList(
                AclEntryPermission.WRITE_DATA,
                AclEntryPermission.APPEND_DATA,
                AclEntryPermission.WRITE_NAMED_ATTRS,
                AclEntryPermission.WRITE_ATTRIBUTES,
                AclEntryPermission.SYNCHRONIZE));
        final Set<AclEntryPermission> executePerms = new HashSet<>(Arrays.asList(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.EXECUTE,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_ACL,
                AclEntryPermission.SYNCHRONIZE));

        UserPrincipalLookupService userPrincipalLookupService = path.getFileSystem().getUserPrincipalLookupService();
        UserPrincipal ownerPrincipal = userPrincipalLookupService.lookupPrincipalByName(ownerUser);
        GroupPrincipal everyone = userPrincipalLookupService.lookupPrincipalByGroupName("Everyone");

        List<AclEntry> aclEntries = new ArrayList<>();
        if (ownerRead) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerPrincipal)
                    .setPermissions(readPerms)
                    .build());
        }
        if (ownerWrite) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerPrincipal)
                    .setPermissions(writePerms)
                    .build());
        }
        if (ownerExecute) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(ownerPrincipal)
                    .setPermissions(executePerms)
                    .build());
        }

        // There is no default group concept on Windows. (There is, but is used when mounting as a network share.)

        if (otherRead) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(everyone)
                    .setPermissions(readPerms)
                    .build());
        }
        if (otherWrite) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(everyone)
                    .setPermissions(writePerms)
                    .build());
        }
        if (otherExecute) {
            aclEntries.add(AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(everyone)
                    .setPermissions(executePerms)
                    .build());
        }

        return aclEntries;
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
