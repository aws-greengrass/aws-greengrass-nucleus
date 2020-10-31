/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.testcommons.testutilities.Matchers.hasPermission;
import static org.hamcrest.MatcherAssert.assertThat;

@ExtendWith(GGExtension.class)
@EnabledOnOs({OS.LINUX, OS.MAC})
class PermissionsTest {

    @TempDir
    Path temp;

    Path testFile;

    Path testDir;

    @BeforeEach
    public void before() throws IOException {
        testFile = Files.createTempFile(temp, "permission", ".txt");
        testDir = Files.createTempDirectory(temp, "dir-test");
    }

    @Test
    void setArtifactPermissionDir() throws Exception {
        // create test artifact file in the test dir to check everything gets updated
        Path artifactFile = Files.createTempFile(testDir, "my-artifact", "bin");
        FileSystemPermission artifactPermission =
                FileSystemPermission.builder()
                        .ownerRead(true).ownerExecute(true)
                        .otherRead(true).build();
        Permissions.setArtifactPermission(testDir, artifactPermission);

        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_EVERYONE_RX));
        assertThat(artifactFile, hasPermission(artifactPermission));
    }

    @Test
    void setArtifactPermissionFile() throws Exception {
        Path artifactFile = Files.createTempFile(testDir, "my-artifact", "bin");
        FileSystemPermission artifactPermission =
                FileSystemPermission.builder()
                        .ownerRead(true).ownerExecute(true)
                        .otherRead(true).build();

        Permissions.setArtifactPermission(artifactFile, artifactPermission);
        assertThat(artifactFile, hasPermission(artifactPermission));
    }

    @Test
    void setComponentStorePermission() throws Exception {
        Permissions.setComponentStorePermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_EVERYONE_RX));
    }

    @Test
    void setArtifactStorePermission() throws Exception {
        Permissions.setArtifactStorePermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_EVERYONE_RX));
    }

    @Test
    void setRecipeStorePermission() throws Exception {
        Permissions.setRecipeStorePermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setWorkPathPermission() throws Exception {
        Permissions.setWorkPathPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_EVERYONE_RX));
    }

    @Test
    void setServiceWorkPathPermission() throws Exception {
        Permissions.setServiceWorkPathPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setRootPermission() throws Exception {
        Permissions.setRootPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_EVERYONE_RX));
    }

    @Test
    void setKernelAltsPermission() throws Exception {
        Permissions.setKernelAltsPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setDeploymentPermission() throws Exception {
        Permissions.setDeploymentPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setConfigPermission() throws Exception  {
        Permissions.setConfigPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setPluginPermission() throws Exception  {
        Permissions.setPluginPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setTelemetryPermission() throws Exception  {
        Permissions.setTelemetryPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setLoggerPermission() throws Exception  {
        Permissions.setLoggerPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_ONLY));
    }

    @Test
    void setCliIpcInfoPermission() throws Exception  {
        Permissions.setCliIpcInfoPermission(testDir);
        assertThat(testDir, hasPermission(Permissions.OWNER_RWX_EVERYONE_RX));
    }

    @Test
    void setIpcSocketPermission() throws Exception {
        Permissions.setIpcSocketPermission(testFile);
        assertThat(testFile, hasPermission(FileSystemPermission.builder()
                .ownerRead(true).ownerWrite(true)
                .groupRead(true).groupWrite(true)
                .otherRead(true).otherWrite(true)
                .build()));
    }
}
