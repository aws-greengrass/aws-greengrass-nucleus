/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.platforms.Platform;
import com.sun.jna.platform.win32.Advapi32Util;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static com.aws.greengrass.integrationtests.BaseITCase.WINDOWS_TEST_PASSWORD;
import static com.aws.greengrass.integrationtests.BaseITCase.createWindowsTestUser;
import static com.aws.greengrass.integrationtests.BaseITCase.deleteWindowsTestUser;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.text.MatchesPattern.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith({GGExtension.class})
@EnabledOnOs(OS.WINDOWS)
class WindowsPlatformTest {

    // Well known Windows group SIDs
    // https://docs.microsoft.com/pt-PT/windows/security/identity-protection/access-control/security-identifiers#well-known-sids
    private static final String EVERYONE_SID = "S-1-1-0";
    private static final String USERS_SID = "S-1-5-32-545";
    // Lookup group name by SID because they may be localized on non-English Windows versions
    private static final String EVERYONE_GROUP_NAME = Advapi32Util.getAccountBySid(EVERYONE_SID).name;
    private static final String USERS_GROUP_NAME = Advapi32Util.getAccountBySid(USERS_SID).name;

    @TempDir
    protected Path tempDir;

    @Test
    void GIVEN_command_WHEN_decorate_THEN_is_decorated() {
        assertThat(new WindowsPlatform.CmdDecorator().decorate("echo", "hello"),
                is(arrayContaining("cmd", "/C", "echo", "hello")));
    }

    @Test
    void GIVEN_the_current_user_WHEN_check_user_exists_THEN_return_true() throws IOException {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsUserAttributes windowsUserAttributes = windowsPlatform.lookupCurrentUser();
        assertThat(windowsPlatform.userExists(windowsUserAttributes.getPrincipalName()), is(true));
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
    void GIVEN_file_system_permission_WHEN_convert_to_acl_THEN_succeed() throws IOException, InterruptedException {
        // No permission
        List<AclEntry> aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView
                .aclEntries(FileSystemPermission.builder().build(), tempDir);
        assertThat(aclEntryList, hasSize(1));

        // Owner
        AclFileAttributeView view = Files.getFileAttributeView(tempDir, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        UserPrincipal owner = view.getOwner();

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerRead(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(owner));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.READ_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerWrite(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(owner));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.WRITE_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerExecute(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(owner));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.EXECUTE_PERMS.toArray()));

        // Group
        UserPrincipalLookupService userPrincipalLookupService = tempDir.getFileSystem().getUserPrincipalLookupService();

        // "Users" is a well known group and should be present on all Windows. Other well known groups that could be
        // used here includes: "Power Users", "Authenticated Users", "Administrators".
        String ownerGroup = USERS_GROUP_NAME;

        GroupPrincipal groupPrincipal = userPrincipalLookupService.lookupPrincipalByGroupName(ownerGroup);
        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerGroup(ownerGroup)
                .groupRead(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(groupPrincipal));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.READ_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerGroup(ownerGroup)
                .groupWrite(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(groupPrincipal));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.WRITE_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .ownerGroup(ownerGroup)
                .groupExecute(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(groupPrincipal));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.EXECUTE_PERMS.toArray()));

        // Other
        GroupPrincipal everyone = userPrincipalLookupService.lookupPrincipalByGroupName(EVERYONE_GROUP_NAME);

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .otherRead(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(everyone));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.READ_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .otherWrite(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(everyone));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.WRITE_PERMS.toArray()));

        aclEntryList = WindowsPlatform.WindowsFileSystemPermissionView.aclEntries(FileSystemPermission.builder()
                .otherExecute(true)
                .build(), tempDir);
        assertThat(aclEntryList, hasSize(2));
        assertThat(aclEntryList.get(0).principal(), equalTo(everyone));
        assertThat(aclEntryList.get(0).type(), equalTo(AclEntryType.ALLOW));
        assertThat(aclEntryList.get(0).permissions(), containsInAnyOrder(WindowsPlatform.EXECUTE_PERMS.toArray()));

        Platform platform = Platform.getInstance();
        Path under = tempDir.resolve("under");
        under.toFile().createNewFile();
        platform.setPermissions(FileSystemPermission.builder()
                        .ownerRead(true)
                        .ownerWrite(true)
                        .ownerExecute(true)
                        .otherWrite(true).build(), under,
                FileSystemPermission.Option.SetMode);

        AclFileAttributeView initialOwnerAcl =
                Files.getFileAttributeView(under, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        int ownerAclCount = 0;
        int ggAclCount = 0;
        int everyoneAclCount = 0;
        for (AclEntry aclEntry : initialOwnerAcl.getAcl()) {
            String name = aclEntry.principal().getName();
            if (name.equals("\\" + EVERYONE_GROUP_NAME)) {
                everyoneAclCount++;
            }
            if (name.contains(platform.getPrivilegedGroup())) {
                ggAclCount++;
            }
            if (name.contains(initialOwnerAcl.getOwner().getName())) {
                ownerAclCount++;
            }
        }
        assertEquals(3 + (initialOwnerAcl.getOwner().getName().contains(platform.getPrivilegedGroup()) ? 1 : 0),
                ownerAclCount);
        assertEquals(1 + (initialOwnerAcl.getOwner().getName().contains(platform.getPrivilegedGroup()) ? 3 : 0),
                ggAclCount);
        assertEquals(1, everyoneAclCount);

        String username = "ABCTEST";
        try {
            createWindowsTestUser(username, WINDOWS_TEST_PASSWORD);
            platform.setPermissions(FileSystemPermission.builder().ownerUser(username).build(), under,
                    FileSystemPermission.Option.SetOwner);
            AclFileAttributeView updatedOwnerAcl = Files.getFileAttributeView(under,
                    AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            assertThat(updatedOwnerAcl.getOwner().getName(), containsString(username));
            List<AclEntry> updatedAcl = updatedOwnerAcl.getAcl();
            assertThat(updatedAcl, hasSize(5));

            ownerAclCount = 0;
            ggAclCount = 0;
            everyoneAclCount = 0;
            for (AclEntry aclEntry : updatedAcl) {
                String name = aclEntry.principal().getName();
                if (name.equals("\\" + EVERYONE_GROUP_NAME)) {
                    everyoneAclCount++;
                }
                if (name.contains(platform.getPrivilegedGroup())) {
                    ggAclCount++;
                }
                if (name.contains(updatedOwnerAcl.getOwner().getName())) {
                    ownerAclCount++;
                }
            }
            assertEquals(3, ownerAclCount);
            assertEquals(1, ggAclCount);
            assertEquals(1, everyoneAclCount);
        } finally {
            deleteWindowsTestUser(username);
        }
    }

    @Test
    void GIVEN_rootPath_of_different_length_WHEN_prepareIpcFilepath_THEN_less_than_max() {
        final int MAX_NAMED_PIPE_LEN = 256;

        WindowsPlatform windowsPlatform = new WindowsPlatform();

        String rootPath = "short";
        String namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath),null);
        assertThat(namedPipe.length(), lessThanOrEqualTo(MAX_NAMED_PIPE_LEN));

        rootPath = String.join("very", Collections.nCopies(300, "long"));
        namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath),null);
        assertThat(namedPipe.length(), lessThanOrEqualTo(MAX_NAMED_PIPE_LEN));
    }

    @Test
    void GIVEN_rootPath_of_different_length_WHEN_prepareIpcFilepath_THEN_good_pattern() {
        final String namedPipePattern = "\\\\\\\\.\\\\pipe\\\\NucleusNamedPipe-[a-zA-Z0-9-]+";

        WindowsPlatform windowsPlatform = new WindowsPlatform();

        String rootPath = "c:\\this\\is\\a\\test";
        String namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath),null);
        assertThat(namedPipe, matchesPattern(namedPipePattern));

        rootPath = String.join("very", Collections.nCopies(300, "long"));
        namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath),null);
        assertThat(namedPipe, matchesPattern(namedPipePattern));
    }

    @Test
    void GIVEN_a_well_known_group_name_WHEN_lookupGroupByName_THEN_succeed() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsGroupAttributes windowsGroupAttributes = windowsPlatform.lookupGroupByName(EVERYONE_GROUP_NAME);
        assertThat(windowsGroupAttributes.getPrincipalIdentifier(), equalTo(EVERYONE_SID));
    }

    @Test
    void GIVEN_a_well_known_group_sid_WHEN_lookupGroupByIdentifier_THEN_succeed() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsGroupAttributes windowsGroupAttributes = windowsPlatform.lookupGroupByIdentifier(EVERYONE_SID);
        assertThat(windowsGroupAttributes.getPrincipalName(), equalTo(EVERYONE_GROUP_NAME));
    }
}
