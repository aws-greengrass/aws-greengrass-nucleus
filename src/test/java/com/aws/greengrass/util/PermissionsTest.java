/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.util;

import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.platforms.Platform;
import com.aws.greengrass.util.platforms.UserPlatform.UserAttributes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.aws.greengrass.testcommons.testutilities.Matchers.hasPermission;
import static com.aws.greengrass.util.FileSystemPermission.Option.SetMode;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class PermissionsTest {

    @TempDir
    Path temp;

    Path testFile;

    Path testDir;

    @Mock
    Platform platform;

    @Mock
    UserAttributes user;

    @AfterAll
    static void resetPlatform() {
        Permissions.platform = Platform.getInstance();
    }

    @BeforeEach
    void before() throws IOException {
        testFile = Files.createTempFile(temp, "permission", ".txt");
        testDir = Files.createTempDirectory(temp, "dir-test");
        lenient().doReturn(user).when(platform).lookupCurrentUser();

        Permissions.platform = platform;
    }

    @Test
    void setArtifactPermissionDirUser() throws Exception {
        // create test artifact file in the test dir to check everything gets updated
        Path artifactFile = Files.createTempFile(testDir, "my-artifact", "bin");
        FileSystemPermission artifactPermission =
                FileSystemPermission.builder()
                        .ownerRead(true).ownerExecute(true)
                        .otherRead(true).build();

        lenient().doReturn(false).when(user).isSuperUser();

        Permissions.setArtifactPermission(testDir, artifactPermission);

        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
        FileSystemPermission withOwnerWrite =
                artifactPermission.toBuilder().ownerWrite(!PlatformResolver.isWindows).build();
        verify(platform).setPermissions(eq(withOwnerWrite), eq(artifactFile), eq(SetMode));
    }

    @Test
    void setArtifactPermissionDirRoot() throws Exception {
        // create test artifact file in the test dir to check everything gets updated
        Path artifactFile = Files.createTempFile(testDir, "my-artifact", "bin");
        FileSystemPermission artifactPermission =
                FileSystemPermission.builder()
                        .ownerRead(true).ownerExecute(true)
                        .otherRead(true).build();

        lenient().doReturn(true).when(user).isSuperUser();

        Permissions.setArtifactPermission(testDir, artifactPermission);

        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
        verify(platform).setPermissions(eq(artifactPermission), eq(artifactFile), eq(SetMode));
    }

    @Test
    void setArtifactPermissionFileUser() throws Exception {
        Path artifactFile = Files.createTempFile(testDir, "my-artifact", "bin");
        FileSystemPermission artifactPermission =
                FileSystemPermission.builder()
                        .ownerRead(true).ownerExecute(true)
                        .otherRead(true).build();

        lenient().doReturn(false).when(user).isSuperUser();

        Permissions.setArtifactPermission(artifactFile, artifactPermission);
        FileSystemPermission withOwnerWrite = artifactPermission.toBuilder()
                .ownerWrite(!PlatformResolver.isWindows).build();
        verify(platform).setPermissions(eq(withOwnerWrite), eq(artifactFile), eq(SetMode));
    }

    @Test
    void setArtifactPermissionFileRoot() throws Exception {
        Path artifactFile = Files.createTempFile(testDir, "my-artifact", "bin");
        FileSystemPermission artifactPermission =
                FileSystemPermission.builder()
                        .ownerRead(true).ownerExecute(true)
                        .otherRead(true).build();

        lenient().doReturn(true).when(user).isSuperUser();

        Permissions.setArtifactPermission(artifactFile, artifactPermission);
        verify(platform).setPermissions(eq(artifactPermission), eq(artifactFile), eq(SetMode));
    }

    @Test
    void setComponentStorePermission() throws Exception {
        Permissions.setComponentStorePermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
    }

    @Test
    void setArtifactStorePermission() throws Exception {
        Permissions.setArtifactStorePermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
    }

    @Test
    void setRecipeStorePermission() throws Exception {
        Permissions.setRecipeStorePermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setWorkPathPermission() throws Exception {
        Permissions.setWorkPathPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
    }

    @Test
    void setServiceWorkPathPermission() throws Exception {
        Permissions.setServiceWorkPathPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setRootPermission() throws Exception {
        Permissions.setRootPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
    }

    @Test
    void setKernelAltsPermission() throws Exception {
        Permissions.setKernelAltsPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setDeploymentPermission() throws Exception {
        Permissions.setDeploymentPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setConfigPermission() throws Exception  {
        Permissions.setConfigPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setPluginPermission() throws Exception  {
        Permissions.setPluginPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setTelemetryPermission() throws Exception  {
        Permissions.setTelemetryPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setLoggerPermission() throws Exception  {
        Permissions.setLoggerPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_ONLY), eq(testDir));
    }

    @Test
    void setCliIpcInfoPermission() throws Exception  {
        Permissions.setCliIpcInfoPermission(testDir);
        verify(platform).setPermissions(eq(Permissions.OWNER_RWX_EVERYONE_RX), eq(testDir));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void setIpcSocketPermission() throws Exception {
        Permissions.setIpcSocketPermission(testFile);
        assertThat(testFile, hasPermission(FileSystemPermission.builder()
                .ownerRead(true).ownerWrite(true)
                .groupRead(true).groupWrite(true)
                .otherRead(true).otherWrite(true)
                .build()));
    }
}
