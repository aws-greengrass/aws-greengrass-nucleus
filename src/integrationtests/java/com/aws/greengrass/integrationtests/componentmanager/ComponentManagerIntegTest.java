/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.GreengrassRepositoryDownloader;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.aws.greengrass.testcommons.testutilities.Matchers.hasPermission;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentManagerIntegTest extends BaseITCase {
    private Kernel kernel;

    private static final String ROOT = Platform.getInstance().getPrivilegedUser();

    @BeforeEach
    void before() {
        kernel = new Kernel();
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_component_with_archived_artifact_WHEN_prepareArtifacts_THEN_unarchives_artifacts() throws Exception {
        // GIVEN
        ComponentIdentifier ident = new ComponentIdentifier("A", new Semver("1.0.0"));

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths);
        kernel.getContext().put(ComponentStore.class, store);
        GreengrassRepositoryDownloader mockDownloader = mock(GreengrassRepositoryDownloader.class);
        kernel.getContext().put(GreengrassRepositoryDownloader.class, mockDownloader);
        File artifactFile = store.resolveArtifactDirectoryPath(ident).resolve("zip.zip").toFile();
        when(mockDownloader.downloadRequired(any(), any(), any())).thenReturn(true);
        when(mockDownloader.getArtifactFile(any(), any(), any())).thenReturn(artifactFile);
        when(mockDownloader.downloadToPath(any(), any(), any())).thenAnswer(downloadToPath("zip.zip", artifactFile));

        ComponentServiceHelper mockServiceHelper = mock(ComponentServiceHelper.class);

        String testRecipeContent =
                FileUtils.readFileToString(Paths.get(this.getClass().getResource("zip.yaml").toURI()).toFile());
        when(mockServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(testRecipeContent);
        kernel.getContext().put(ComponentServiceHelper.class, mockServiceHelper);

        // THEN
        kernel.getContext().get(ComponentManager.class).preparePackages(Collections.singletonList(ident))
                .get(10, TimeUnit.SECONDS);

        Path zipPath = nucleusPaths.unarchiveArtifactPath(ident, "zip");
        assertThat(zipPath.toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").resolve("1").toFile(), anExistingFile());
        assertThat(zipPath.resolve("zip").resolve("2").toFile(), anExistingFile());

        // check everyone can enter dir
        assertThat(zipPath.resolve("zip"), hasPermission(FileSystemPermission.builder()
                        .ownerRead(true).ownerWrite(true).ownerExecute(true)
                        .groupRead(true).groupExecute(true)
                        .otherRead(true).otherExecute(true).build()));

        // check perms match what we gave
        FileSystemPermission allRead = FileSystemPermission.builder()
                .ownerRead(true).groupRead(true).otherRead(true)
                .ownerWrite(!SystemUtils.USER_NAME.equals(ROOT)) // we preserve write permissions for non-root user
                .build();

        assertThat(zipPath.resolve("zip").resolve("1"), hasPermission(allRead));
        assertThat(zipPath.resolve("zip").resolve("2"), hasPermission(allRead));
    }

    @Test
    void GIVEN_component_with_artifact_WHEN_prepareArtifacts_THEN_set_permissions_on_artifacts() throws Exception {
        ComponentIdentifier ident = new ComponentIdentifier("A", new Semver("1.0.0"));

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths);
        kernel.getContext().put(ComponentStore.class, store);
        GreengrassRepositoryDownloader mockDownloader = mock(GreengrassRepositoryDownloader.class);
        kernel.getContext().put(GreengrassRepositoryDownloader.class, mockDownloader);
        File scriptFile = store.resolveArtifactDirectoryPath(ident).resolve("script.sh").toFile();
        File emptyFile = store.resolveArtifactDirectoryPath(ident).resolve("empty.txt").toFile();
        when(mockDownloader.downloadRequired(any(), any(), any())).thenReturn(true);
        when(mockDownloader.getArtifactFile(any(), any(), any())).thenReturn(scriptFile).thenReturn(emptyFile);

        when(mockDownloader.downloadToPath(any(), any(), any()))
                .thenAnswer(downloadToPath("script.sh", scriptFile))
                .thenAnswer(downloadToPath("empty.txt", emptyFile));

        ComponentServiceHelper mockServiceHelper = mock(ComponentServiceHelper.class);

        String testRecipeContent =
                FileUtils.readFileToString(Paths.get(this.getClass().getResource("perms.yaml").toURI()).toFile());
        when(mockServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(testRecipeContent);
        kernel.getContext().put(ComponentServiceHelper.class, mockServiceHelper);

        // THEN
        kernel.getContext().get(ComponentManager.class).preparePackages(Collections.singletonList(ident))
                .get(10, TimeUnit.SECONDS);
        assertThat(nucleusPaths.artifactPath(ident).resolve("script.sh"), hasPermission(FileSystemPermission.builder()
                .ownerRead(true).groupRead(true).otherRead(true)
                .ownerWrite(!SystemUtils.USER_NAME.equals(ROOT)) // we preserve write permissions for  non-root user
                .ownerExecute(true).groupExecute(true)
                .build()));

        assertThat(nucleusPaths.artifactPath(ident).resolve("empty.txt"), hasPermission(FileSystemPermission.builder()
                .ownerRead(true).groupRead(true)
                .ownerWrite(!SystemUtils.USER_NAME.equals(ROOT)) // we preserve write permissions for non-root user
                .build()));
    }

    /**
     * Mock download a file to a path using a class resource.
     *
     * @param resource the resource to copy
     * @param f the file location to copy to.
     * @return an answer that can be used for mocking.
     */
    Answer<File> downloadToPath(String resource, File f) {
        return (i) -> {
            Files.copy(getClass().getResourceAsStream(resource), f.toPath());
            return f;
        };
    }
}
