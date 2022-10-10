/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms.unix;

import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.FileSystemPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;


@ExtendWith({MockitoExtension.class, GGExtension.class})
@DisabledOnOs(OS.WINDOWS)
class UnixPlatformTest   {

    private static String[] command = {"echo", "hello", "world"};
    private static final Logger logger = LogManager.getLogger(UnixPlatformTest.class);

    @Spy
    private final UnixPlatform spyPlatform = new UnixPlatform();
    private final static String user = "ggc_user01";
    private final static String group = "ggc_group01";

    @Test
    public void GIVEN_busybox_environment_WHEN_add_user_THEN_add_user_success() {
        try {
            spyPlatform.runCmd("useradd -r -m " + user, o -> {
            }, "Failed to create user with useradd");
        } catch (IOException e) {
            logger.info(e.getMessage());
        }

        try {
            spyPlatform.createUser(user);
        } catch (IOException e) {
            Throwable throwable = assertThrows(IOException.class, () -> spyPlatform.createUser(user));
            Throwable[] suppressed = throwable.getSuppressed();
            assertThat(throwable.getMessage(), containsString("useradd"));
            assertThat(String.valueOf(Arrays.stream(suppressed).anyMatch(item -> item.toString().contains("adduser"))), true);
        }
    }

    @Test
    public void GIVEN_busybox_environment_WHEN_add_group_THEN_add_group_success() {
        try {
            spyPlatform.runCmd("groupadd -r " + group, o -> {
            }, "Failed to create group with groupadd");
        } catch (IOException e) {
            logger.info(e.getMessage());
        }

        try {
            spyPlatform.createGroup(group);
        } catch (IOException e) {
            Throwable throwable = assertThrows(IOException.class, () -> spyPlatform.createGroup(group));
            Throwable[] suppressed = throwable.getSuppressed();
            assertThat(String.valueOf(Arrays.stream(suppressed).anyMatch(item -> item.toString().contains("addgroup"))), true);
            assertThat(throwable.getMessage(), containsString("groupadd"));
        }
    }

    @Test
    public void GIVEN_not_unix_platform_WHEN_add_user_to_group_THEN_user_added_to_group_successfully() {
        try {
            spyPlatform.runCmd("groupdel " + group, o -> {
            }, "Failed to del group with groupdel");
        } catch (IOException e) {
            logger.info(e.getMessage());
        }

        try {
            spyPlatform.addUserToGroup(user, group);
        } catch (IOException e) {
            Throwable throwable = assertThrows(IOException.class, () -> spyPlatform.addUserToGroup(user, group));
            Throwable[] suppressed = throwable.getSuppressed();
            assertThat(throwable.getMessage(), containsString("usermod"));
            assertThat(String.valueOf(Arrays.stream(suppressed).anyMatch(item -> item.toString().contains("addgroup ggc_user01 ggc_group01"))), true);
        }
    }

    @Test
    void GIVEN_no_user_and_no_group_WHEN_decorate_THEN_do_not_generate_sudo_with_user_and_group() {
        assertThat(new UnixPlatform.SudoDecorator().decorate(command),
                is(arrayContaining(command)));
    }

    @Test
    void GIVEN_user_and_group_WHEN_decorate_THEN_generate_sudo_with_user_and_group() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("foo")
                        .withGroup("bar")
                        .decorate(command),
                is(arrayContaining("sudo", "-n",  "-E", "-H", "-u", "foo", "-g", "bar", "--", "echo", "hello",
                        "world")));
    }

    @Test
    void GIVEN_numeric_user_and_group_WHEN_decorate_THEN_generate_sudo_with_prefix() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("100")
                        .withGroup("200")
                        .decorate(command),

                is(arrayContaining("sudo", "-n", "-E", "-H", "-u", "#100", "-g", "#200", "--", "echo", "hello",
                        "world")));
    }

    @Test
    void GIVEN_user_WHEN_decorate_THEN_generate_sudo_without_group() {
        assertThat(new UnixPlatform.SudoDecorator()
                        .withUser("foo")
                        .decorate(command),
                is(arrayContaining("sudo", "-n", "-E", "-H", "-u", "foo", "--", "echo", "hello", "world")));
    }

    @Test
    void GIVEN_file_system_permission_WHEN_convert_to_posix_THEN_succeed() {
        // Nothing
        Set<PosixFilePermission> permissions =
                UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder().build());
        assertThat(permissions, empty());

        // Owner
        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .ownerRead(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.OWNER_READ));

        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .ownerWrite(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.OWNER_WRITE));

        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .ownerExecute(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.OWNER_EXECUTE));

        // Group
        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .groupRead(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.GROUP_READ));

        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .groupWrite(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.GROUP_WRITE));

        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .groupExecute(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.GROUP_EXECUTE));

        // Other
        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .otherRead(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.OTHERS_READ));

        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .otherWrite(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.OTHERS_WRITE));

        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .otherExecute(true)
                .build());
        assertThat(permissions, contains(PosixFilePermission.OTHERS_EXECUTE));

        // Everything
        permissions = UnixPlatform.PosixFileSystemPermissionView.posixFilePermissions(FileSystemPermission.builder()
                .ownerRead(true)
                .ownerWrite(true)
                .ownerExecute(true)
                .groupRead(true)
                .groupWrite(true)
                .groupExecute(true)
                .otherRead(true)
                .otherWrite(true)
                .otherExecute(true)
                .build());
        assertThat(permissions, containsInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_WRITE,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_WRITE,
                PosixFilePermission.OTHERS_EXECUTE));
    }
}
