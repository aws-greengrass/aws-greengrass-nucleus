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

    // The tests usually set permission twice, verifying the permission after each time. This is the permission to be
    // applied first. It is also applied to a directory.
    //
    // On Linux, for a directory, we needs "owner execute" permission so that the test can change into a subdirectory.
    //
    // On Windows, for a directory, we needs "owner read" permission in order to set the permission the 2nd time.
    private static final FileSystemPermission MIN_PERMISSION = FileSystemPermission.builder()
            .ownerRead(true)
            .ownerExecute(true)
            .build();

    @TempDir
    protected Path tempDir;

    @Test
    void GIVEN_file_WHEN_set_owner_mode_THEN_succeed() throws IOException {
        Path tempFile = Files.createTempFile(tempDir, null, null);
        FileSystemPermission expectedPermission = FileSystemPermission.builder()
                .ownerRead(true)
                .ownerWrite(true)
                .ownerExecute(true)
                .build();

        PLATFORM.setPermissions(MIN_PERMISSION, tempFile);
        assertThat(tempFile, hasPermission(MIN_PERMISSION));

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

        PLATFORM.setPermissions(MIN_PERMISSION, tempFile);
        assertThat(tempFile, hasPermission(MIN_PERMISSION));

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

        PLATFORM.setPermissions(MIN_PERMISSION, tempFile);
        assertThat(tempFile, hasPermission(MIN_PERMISSION));

        PLATFORM.setPermissions(expectedPermission, tempFile);
        assertThat(tempFile, hasPermission(expectedPermission));
    }

    @Test
    void GIVEN_non_empty_dir_WHEN_set_mode_recurse_THEN_succeed() throws IOException {
        Path tempSubDir = Files.createTempDirectory(tempDir, null);
        Path tempFile = Files.createTempFile(tempSubDir, null, null);

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

        PLATFORM.setPermissions(MIN_PERMISSION, tempSubDir, FileSystemPermission.Option.SetMode,
                FileSystemPermission.Option.Recurse);
        assertThat(tempSubDir, hasPermission(MIN_PERMISSION));
        assertThat(tempFile, hasPermission(MIN_PERMISSION));

        PLATFORM.setPermissions(expectedPermission, tempSubDir, FileSystemPermission.Option.SetMode,
                FileSystemPermission.Option.Recurse);
        assertThat(tempSubDir, hasPermission(expectedPermission));
        assertThat(tempFile, hasPermission(expectedPermission));
    }

    @Test
    void GIVEN_non_exist_file_WHEN_setPermissions_THEN_throw() {
        Path nonExistingFile = tempDir.resolve(UUID.randomUUID().toString());
        assertThrows(IOException.class, () -> {
            PLATFORM.setPermissions(MIN_PERMISSION, nonExistingFile);
        });
    }

    // Nice to have tests:
    // GIVEN_file_WHEN_set_owner_THEN_succeed
    // GIVEN_non_empty_dir_WHEN_set_owner_recurse_THEN_succeed
    // GIVEN_file_without_permission_WHEN_setPermissions_THEN_throw
}
