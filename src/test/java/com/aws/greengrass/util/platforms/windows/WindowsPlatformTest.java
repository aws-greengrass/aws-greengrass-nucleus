/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.windows;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.FileSystemPermission;
import com.sun.jna.platform.win32.AccCtrl;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Kernel32Util;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileWriter;
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

import static com.sun.jna.platform.win32.AccCtrl.SE_OBJECT_TYPE.SE_KERNEL_OBJECT;
import static com.sun.jna.platform.win32.WinBase.PIPE_ACCEPT_REMOTE_CLIENTS;
import static com.sun.jna.platform.win32.WinBase.PIPE_ACCESS_DUPLEX;
import static com.sun.jna.platform.win32.WinBase.PIPE_READMODE_BYTE;
import static com.sun.jna.platform.win32.WinBase.PIPE_TYPE_BYTE;
import static com.sun.jna.platform.win32.WinBase.PIPE_UNLIMITED_INSTANCES;
import static com.sun.jna.platform.win32.WinBase.PIPE_WAIT;
import static com.sun.jna.platform.win32.WinNT.DACL_SECURITY_INFORMATION;
import static com.sun.jna.platform.win32.WinNT.FILE_FLAG_OVERLAPPED;
import static com.sun.jna.platform.win32.WinNT.GROUP_SECURITY_INFORMATION;
import static com.sun.jna.platform.win32.WinNT.OWNER_SECURITY_INFORMATION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.text.MatchesPattern.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith({GGExtension.class})
@EnabledOnOs(OS.WINDOWS)
class WindowsPlatformTest {

    // This is a well known Windows group and its Sid.
    private static final String EVERYONE = "Everyone";
    private static final String EVERYONE_SID = "S-1-1-0";

    @TempDir
    protected Path tempDir;

    @Test
    void GIVEN_command_WHEN_decorate_THEN_is_decorated() {
        assertThat(new WindowsPlatform.CmdDecorator()
                        .decorate("echo", "hello"),
                is(arrayContaining("cmd.exe", "/C", "echo", "hello")));
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

    @Test
    void GIVEN_rootPath_of_different_length_WHEN_prepareIpcFilepath_THEN_less_than_max() {
        final int MAX_NAMED_PIPE_LEN = 256;

        WindowsPlatform windowsPlatform = new WindowsPlatform();

        String rootPath = "short";
        String namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath));
        assertThat(namedPipe.length(), lessThanOrEqualTo(MAX_NAMED_PIPE_LEN));

        rootPath = String.join("very", Collections.nCopies(300, "long"));
        namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath));
        assertThat(namedPipe.length(), lessThanOrEqualTo(MAX_NAMED_PIPE_LEN));
    }

    @Test
    void GIVEN_rootPath_of_different_length_WHEN_prepareIpcFilepath_THEN_good_pattern() {
        final String namedPipePattern = "\\\\\\\\.\\\\pipe\\\\NucleusNamedPipe-[a-zA-Z0-9-]+";

        WindowsPlatform windowsPlatform = new WindowsPlatform();

        String rootPath = "c:\\this\\is\\a\\test";
        String namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath));
        assertThat(namedPipe, matchesPattern(namedPipePattern));

        rootPath = String.join("very", Collections.nCopies(300, "long"));
        namedPipe = windowsPlatform.prepareIpcFilepath(Paths.get(rootPath));
        assertThat(namedPipe, matchesPattern(namedPipePattern));
    }

    @Test
    void GIVEN_a_well_known_group_name_WHEN_lookupGroupByName_THEN_succeed() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsGroupAttributes windowsGroupAttributes = windowsPlatform.lookupGroupByName(EVERYONE);
        assertThat(windowsGroupAttributes.getPrincipalIdentifier(), equalTo(EVERYONE_SID));
    }

    @Test
    void GIVEN_a_well_known_group_sid_WHEN_lookupGroupByIdentifier_THEN_succeed() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();
        WindowsGroupAttributes windowsGroupAttributes = windowsPlatform.lookupGroupByIdentifier(EVERYONE_SID);
        assertThat(windowsGroupAttributes.getPrincipalName(), equalTo(EVERYONE));
    }

    @Test
    void GIVEN_a_named_pipe_WHEN_setIpcFilePermissions_THEN_named_pipe_is_public_accessible() {
        WindowsPlatform windowsPlatform = new WindowsPlatform();

        // Create a named pipe, passing null as lpSecurityAttributes (sane as Device SDK)
        String namedPipe = windowsPlatform.prepareIpcFilepath(tempDir);
        WinNT.HANDLE handle = Kernel32.INSTANCE.CreateNamedPipe(namedPipe,  // lpName
                PIPE_ACCESS_DUPLEX | FILE_FLAG_OVERLAPPED,  // dwOpenMode
                PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_ACCEPT_REMOTE_CLIENTS, // dwPipeMode
                PIPE_UNLIMITED_INSTANCES, // nMaxInstances
                512, // nOutBufferSize
                512, // nInBufferSize
                0, // nDefaultTimeOut
                null); // lpSecurityAttributes
        assertFalse(WinBase.INVALID_HANDLE_VALUE.equals(handle));

        // Test access right
        int infoType = OWNER_SECURITY_INFORMATION
                | GROUP_SECURITY_INFORMATION
                | DACL_SECURITY_INFORMATION;

        PointerByReference ppsidOwner = new PointerByReference();
        PointerByReference ppsidGroup = new PointerByReference();
        PointerByReference ppDacl = new PointerByReference();
        PointerByReference ppSecurityDescriptor = new PointerByReference();

        int ret = Advapi32.INSTANCE.GetSecurityInfo(handle,
                SE_KERNEL_OBJECT,
                infoType,
                ppsidOwner,
                ppsidGroup,
                ppDacl,
                null,
                ppSecurityDescriptor);
        assertEquals(0, ret);

        // Change access right

        // This code should be implemented in WindowsPlatform.setIpcFilePermissions.
        // windowsPlatform.setIpcFilePermissions(tempDir);
        ret = Advapi32.INSTANCE.SetSecurityInfo(handle,
                SE_KERNEL_OBJECT,
                infoType,
                ppsidOwner.getValue(),
                ppsidGroup.getValue(),

                // https://docs.microsoft.com/en-us/windows/win32/secauthz/access-control-lists
                // "If the object does not have a DACL, the system grants full access to everyone."
                ppDacl.getValue(),

                null);
        assertEquals(0, ret); // ERROR_ACCESS_DENIED

        // Test access right again
        ppsidOwner = new PointerByReference();
        ppsidGroup = new PointerByReference();
        ppDacl = new PointerByReference();
        ppSecurityDescriptor = new PointerByReference();

        ret = Advapi32.INSTANCE.GetSecurityInfo(handle,
                SE_KERNEL_OBJECT,
                infoType,
                ppsidOwner,
                ppsidGroup,
                ppDacl,
                null,
                ppSecurityDescriptor);
        assertEquals(0, ret);
    }

    // Copied from: https://github.com/java-native-access/jna/blob/master/contrib/platform/test/com/sun/jna/platform/win32/Advapi32Test.java#L1319
    @Test
    public void testGetSetSecurityInfoNoSACL() throws Exception {
        int infoType = OWNER_SECURITY_INFORMATION
                | GROUP_SECURITY_INFORMATION
                | DACL_SECURITY_INFORMATION;

        PointerByReference ppsidOwner = new PointerByReference();
        PointerByReference ppsidGroup = new PointerByReference();
        PointerByReference ppDacl = new PointerByReference();
        PointerByReference ppSecurityDescriptor = new PointerByReference();
        // create a temp file
        File file = createTempFile();
        String filePath = file.getAbsolutePath();

        WinNT.HANDLE hFile = Kernel32.INSTANCE.CreateFile(
                filePath,
                WinNT.GENERIC_ALL | WinNT.WRITE_OWNER | WinNT.WRITE_DAC,
                WinNT.FILE_SHARE_READ,
                new WinBase.SECURITY_ATTRIBUTES(),
                WinNT.OPEN_EXISTING,
                WinNT.FILE_ATTRIBUTE_NORMAL,
                null);
        assertFalse(WinBase.INVALID_HANDLE_VALUE.equals(hFile));

        try {
            try {

                assertEquals(0,
                        Advapi32.INSTANCE.GetSecurityInfo(
                                hFile,
                                AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                                infoType,
                                ppsidOwner,
                                ppsidGroup,
                                ppDacl,
                                null,
                                ppSecurityDescriptor));

                assertEquals(0,
                        Advapi32.INSTANCE.SetSecurityInfo(
                                hFile,
                                AccCtrl.SE_OBJECT_TYPE.SE_FILE_OBJECT,
                                infoType,
                                ppsidOwner.getValue(),
                                ppsidGroup.getValue(),
                                ppDacl.getValue(),
                                null));
            } finally {
                Kernel32.INSTANCE.CloseHandle(hFile);
                file.delete();
            }
        } finally {
            Kernel32Util.freeLocalMemory(ppSecurityDescriptor.getValue());
        }
    }

    private File createTempFile() throws Exception {
        String filePath = System.getProperty("java.io.tmpdir") + System.nanoTime()
                + ".text";
        File file = new File(filePath);
        file.createNewFile();
        FileWriter fileWriter = new FileWriter(file);
        for (int i = 0; i < 1000; i++) {
            fileWriter.write("Sample text " + i + System.getProperty("line.separator"));
        }
        fileWriter.close();
        return file;
    }
}
