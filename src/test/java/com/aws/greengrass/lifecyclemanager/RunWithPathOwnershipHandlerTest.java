/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Arrays;
import java.util.Iterator;

import static com.aws.greengrass.util.FileSystemPermission.Option.Recurse;
import static com.aws.greengrass.util.FileSystemPermission.Option.SetOwner;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({
        GGExtension.class, MockitoExtension.class
})
class RunWithPathOwnershipHandlerTest {

    RunWithPathOwnershipHandler handler;

    @Mock
    NucleusPaths paths;

    @Mock
    Platform platform;

    @Mock
    ComponentIdentifier id;

    @Mock
    RunWith runWith;

    @Mock
    Path existing;

    @Mock
    Path nonExisting;

    @Mock
    Path firstPath;

    @Mock
    Path secondPath;

    @Mock
    Path workPath;

    @SuppressWarnings("PMD.CloseResource")
    @BeforeEach
    void beforeEach() throws IOException {
        FileSystem fs = mock(FileSystem.class);
        FileSystemProvider fsProvider = mock(FileSystemProvider.class);

        lenient().doReturn(fsProvider).when(fs).provider();

        DirectoryStream<Path> ds = mock(DirectoryStream.class);
        lenient().doReturn(ds).when(fsProvider).newDirectoryStream(eq(existing), any());

        Iterator<Path> firstIteration = Arrays.stream(new Path[] {
                firstPath
        }).iterator();
        Iterator<Path> secondIteration = Arrays.stream(new Path[] {
                secondPath
        }).iterator();
        lenient().doReturn(firstIteration, secondIteration).when(ds).iterator();

        lenient().doThrow(NoSuchFileException.class).when(fsProvider).checkAccess(eq(nonExisting));

        lenient().doReturn(fs).when(existing).getFileSystem();
        lenient().doReturn(fs).when(nonExisting).getFileSystem();
        lenient().doReturn(fs).when(workPath).getFileSystem();

        doReturn("foo").when(runWith).getUser();
        doReturn("bar").when(runWith).getGroup();
        handler = new RunWithPathOwnershipHandler(paths, platform);
    }

    @Test
    void GIVEN_paths_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(existing).when(paths).artifactPath(id);
        doReturn(existing).when(paths).unarchiveArtifactPath(id);
        doReturn(workPath).when(paths).workPath(any());
        handler.updateOwner(id, runWith);

        ArgumentCaptor<FileSystemPermission> permissions = ArgumentCaptor.forClass(FileSystemPermission.class);
        verify(platform).setPermissions(permissions.capture(), eq(firstPath), eq(Recurse), eq(SetOwner));
        verify(platform).setPermissions(permissions.capture(), eq(secondPath), eq(Recurse), eq(SetOwner));
        verify(platform).setPermissions(permissions.capture(), eq(workPath), eq(Recurse), eq(SetOwner));

        permissions.getAllValues().forEach(p -> {
            assertThat(p.getOwnerUser(), is("foo"));
            assertThat(p.getOwnerGroup(), is("bar"));
        });
    }

    @Test
    void GIVEN_archive_path_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(existing).when(paths).artifactPath(id);
        doReturn(nonExisting).when(paths).unarchiveArtifactPath(id);
        doReturn(nonExisting).when(paths).workPath(any());

        handler.updateOwner(id, runWith);

        verify(platform).setPermissions(any(), eq(firstPath), eq(Recurse), eq(SetOwner));

    }

    @Test
    void GIVEN_unarchive_path_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(nonExisting).when(paths).artifactPath(id);
        doReturn(existing).when(paths).unarchiveArtifactPath(id);
        doReturn(nonExisting).when(paths).workPath(any());
        handler.updateOwner(id, runWith);

        verify(platform).setPermissions(any(), eq(firstPath), eq(Recurse), eq(SetOwner));
    }

    @Test
    void GIVEN_no_path_and_run_with_WHEN_updateOwner_THEN_no_update_paths() throws IOException {
        doReturn(nonExisting).when(paths).artifactPath(id);
        doReturn(nonExisting).when(paths).unarchiveArtifactPath(id);
        doReturn(nonExisting).when(paths).workPath(any());
        handler.updateOwner(id, runWith);

        verify(platform, times(0)).setPermissions(any(), eq(nonExisting), eq(Recurse), eq(SetOwner));
    }

    @Test
    void GIVEN_work_path_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(nonExisting).when(paths).artifactPath(id);
        doReturn(nonExisting).when(paths).unarchiveArtifactPath(id);
        doReturn(workPath).when(paths).workPath(any());
        handler.updateOwner(id, runWith);

        verify(platform).setPermissions(any(), eq(workPath), eq(Recurse), eq(SetOwner));
    }
}
