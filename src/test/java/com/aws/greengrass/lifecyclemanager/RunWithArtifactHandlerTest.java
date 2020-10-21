/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

import static com.aws.greengrass.util.FileSystemPermission.Option.IgnorePermission;
import static com.aws.greengrass.util.FileSystemPermission.Option.Recurse;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class RunWithArtifactHandlerTest {

    RunWithArtifactHandler handler;

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

    @BeforeEach
    void beforeEach() {
        File existingFile = mock(File.class);
        lenient().doReturn(true).when(existingFile).exists();
        lenient().doReturn(existingFile).when(existing).toFile();

        File nonExistingFile = mock(File.class);
        lenient().doReturn(false).when(nonExistingFile).exists();
        lenient().doReturn(nonExistingFile).when(nonExisting).toFile();

        doReturn("foo").when(runWith).getUser();
        doReturn("bar").when(runWith).getGroup();
        handler = new RunWithArtifactHandler(paths, platform);
    }

    @Test
    public void GIVEN_paths_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(existing).when(paths).artifactPath(id);
        doReturn(existing).when(paths).unarchiveArtifactPath(id);
        handler.updateOwner(id, runWith);

        ArgumentCaptor<FileSystemPermission> permissions = ArgumentCaptor.forClass(FileSystemPermission.class);
        verify(platform, times(2))
                .setPermissions(permissions.capture(), eq(existing), eq(Recurse), eq(IgnorePermission));

        permissions.getAllValues().forEach(p -> {
            assertThat(p.getOwnerUser(), is("foo"));
            assertThat(p.getOwnerGroup(), is("bar"));
        });
    }

    @Test
    public void GIVEN_archive_path_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(existing).when(paths).artifactPath(id);
        doReturn(nonExisting).when(paths).unarchiveArtifactPath(id);
        handler.updateOwner(id, runWith);

        verify(platform).setPermissions(any(), eq(existing), eq(Recurse), eq(IgnorePermission));

    }

    @Test
    public void GIVEN_unarchive_path_and_run_with_WHEN_updateOwner_THEN_update_paths() throws IOException {
        doReturn(nonExisting).when(paths).artifactPath(id);
        doReturn(existing).when(paths).unarchiveArtifactPath(id);
        handler.updateOwner(id, runWith);

        verify(platform).setPermissions(any(), eq(existing), eq(Recurse), eq(IgnorePermission));
    }

    @Test
    public void GIVEN_no_path_and_run_with_WHEN_updateOwner_THEN_no_update_paths() throws IOException {
        doReturn(nonExisting).when(paths).artifactPath(id);
        doReturn(nonExisting).when(paths).unarchiveArtifactPath(id);
        handler.updateOwner(id, runWith);

        verify(platform, times(0)).setPermissions(any(), eq(nonExisting), eq(Recurse), eq(IgnorePermission));
    }
}
