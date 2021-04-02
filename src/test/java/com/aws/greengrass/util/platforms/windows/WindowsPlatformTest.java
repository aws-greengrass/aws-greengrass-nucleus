/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.FileSystemPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

@ExtendWith({GGExtension.class})
@EnabledOnOs(OS.WINDOWS)
class WindowsPlatformTest {

    @TempDir
    protected Path tempDir;

    @Test
    void GIVEN_command_WHEN_decorate_THEN_is_decorated() {
        assertThat(new WindowsPlatform.CmdDecorator()
                        .decorate("echo", "hello"),
                is(arrayContaining("cmd.exe", "/C", "echo", "hello")));
    }

    @Test
    void GIVEN_administrator_username_WHEN_check_user_exists_THEN_return_true() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        assertThat(windowsPlatform.userExists("Administrator"), is(true));
    }

    @Test
    void GIVEN_random_string_as_username_WHEN_check_user_exists_THEN_return_false() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        assertThat(windowsPlatform.userExists(UUID.randomUUID().toString()), is(false));
    }

    @Test
    void WHEN_lookup_current_user_THEN_get_user_attributes() throws IOException {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsUserAttributes windowsUserAttributes = windowsPlatform.lookupCurrentUser();
        assertThat(windowsUserAttributes.getPrincipalName(), not(emptyOrNullString()));
        assertThat(windowsUserAttributes.getPrincipalIdentifier(), not(emptyOrNullString()));
    }

    @Test
    void GIVEN_file_system_permission_WHEN_convert_to_acl_THEN_succeed() throws IOException {
        // No permission
        List<AclEntry> aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView
                .aclEntries(FileSystemPermission.builder().build(), tempDir);
        assertThat(aclEntryList, empty());

        // Owner
        AclFileAttributeView view = Files.getFileAttributeView(tempDir, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = view.getOwner();

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerRead(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(owner));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.READ_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerWrite(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(owner));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.WRITE_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerExecute(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(owner));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.EXECUTE_PERMS.toArray()));

        // Group
        UserPrincipalLookupService userPrincipalLookupService = tempDir.getFileSystem().getUserPrincipalLookupService();

        // "Users" is a well known group and should be present on all Windows. Other well known groups that could be
        // used here includes: "Power Users", "Authenticated Users", "Administrators".
        String ownerGroup = "Users";

        GroupPrincipal groupPrincipal = userPrincipalLookupService.lookupPrincipalByGroupName(ownerGroup);
        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerGroup(ownerGroup)
                .groupRead(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(groupPrincipal));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.READ_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerGroup(ownerGroup)
                .groupWrite(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(groupPrincipal));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.WRITE_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerGroup(ownerGroup)
                .groupExecute(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(groupPrincipal));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.EXECUTE_PERMS.toArray()));

        // Other
        GroupPrincipal everyone = userPrincipalLookupService.lookupPrincipalByGroupName("Everyone");

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .otherRead(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(everyone));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.READ_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .otherWrite(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(everyone));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.WRITE_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .otherExecute(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(1));
        assertThat(aclEntryList.get(0).principal(), equalTo(everyone));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.EXECUTE_PERMS.toArray()));
    }
}
