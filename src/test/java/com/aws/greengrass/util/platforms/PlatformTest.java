/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util.platforms;

import com.aws.greengrass.util.FileSystemPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static com.aws.greengrass.testcommons.testutilities.Matchers.hasPermission;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PlatformTest {

    private static final Platform PLATFORM = Platform.getInstance();
    private static final FileSystemPermission NO_PERMISSION = FileSystemPermission.builder().build();

    @TempDir
    protected Path tempDir;

    @Test
    void GIVEN_file_WHEN_set_owner_THEN_succeed() {
    }

    @Test
    void GIVEN_file_WHEN_set_owner_mode_THEN_succeed() throws IOException {
        Path tempFile = Files.createTempFile(tempDir, null, null);
        FileSystemPermission expectedPermission = FileSystemPermission.builder()
                .ownerRead(true)
                .ownerWrite(true)
                .ownerExecute(true)
                .build();

        PLATFORM.setPermissions(NO_PERMISSION, tempFile);
        assertThat(tempFile, hasPermission(NO_PERMISSION));

        PLATFORM.setPermissions(expectedPermission, tempFile);
        assertThat(tempFile, hasPermission(expectedPermission));
    }

    @Test
    void GIVEN_file_WHEN_set_group_mode_THEN_succeed() throws IOException {
        Path tempFile = Files.createTempFile(tempDir, null, null);
        FileSystemPermission expectedPermission = FileSystemPermission.builder()
                .groupRead(true)
                .groupWrite(true)
                .groupExecute(true)
                .build();

        PLATFORM.setPermissions(NO_PERMISSION, tempFile);
        assertThat(tempFile, hasPermission(NO_PERMISSION));

        PLATFORM.setPermissions(expectedPermission, tempFile);
        assertThat(tempFile, hasPermission(expectedPermission));
    }

    @Test
    void GIVEN_file_WHEN_set_other_mode_THEN_succeed() throws IOException {
        Path tempFile = Files.createTempFile(tempDir, null, null);
        FileSystemPermission expectedPermission = FileSystemPermission.builder()
                .otherRead(true)
                .otherWrite(true)
                .otherExecute(true)
                .build();

        PLATFORM.setPermissions(NO_PERMISSION, tempFile);
        assertThat(tempFile, hasPermission(NO_PERMISSION));

        PLATFORM.setPermissions(expectedPermission, tempFile);
        assertThat(tempFile, hasPermission(expectedPermission));
    }

    @Test
    void GIVEN_non_empty_dir_WHEN_set_owner_recurse_THEN_succeed() {
    }

    @Test
    void GIVEN_non_empty_dir_WHEN_set_mode_recurse_THEN_succeed() throws IOException {
        Path tempSubDir = Files.createTempDirectory(tempDir, null);
        Path tempFile = Files.createTempFile(tempSubDir, null, null);

        // This test sets the permission twice, verifying the permission after each time. This is the permission to
        // be applied first. It is also applied to a directory. On Windows, for directory, it needs at least "owner
        // read" permission in order to set the permission the 2nd time.
        FileSystemPermission minPermission = FileSystemPermission.builder()
                .ownerRead(true)
                .build();

        FileSystemPermission expectedPermission = FileSystemPermission.builder()
                .ownerRead(true)
                .ownerWrite(true)
                .ownerExecute(true)
                .groupRead(true)
                .groupWrite(true)
                .groupExecute(true)
                .otherRead(true)
                .otherWrite(true)
                .otherExecute(true)
                .build();

        PLATFORM.setPermissions(minPermission, tempSubDir, FileSystemPermission.Option.SetMode,
                FileSystemPermission.Option.Recurse);
        assertThat(tempSubDir, hasPermission(minPermission));
        assertThat(tempFile, hasPermission(minPermission));

        PLATFORM.setPermissions(expectedPermission, tempSubDir, FileSystemPermission.Option.SetMode,
                FileSystemPermission.Option.Recurse);
        assertThat(tempSubDir, hasPermission(expectedPermission));
        assertThat(tempFile, hasPermission(expectedPermission));
    }

    @Test
    void GIVEN_non_exist_file_WHEN_setPermissions_THEN_throw() {
        Path nonExistingFile = tempDir.resolve(UUID.randomUUID().toString());
        assertThrows(IOException.class, () -> {
            PLATFORM.setPermissions(NO_PERMISSION, nonExistingFile);
        });
    }

    @Test
    void GIVEN_file_without_permission_WHEN_setPermissions_THEN_throw() throws IOException {
    }
}
