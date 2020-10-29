/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager;

import com.aws.greengrass.componentmanager.ComponentServiceHelper;
import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.GreengrassRepositoryDownloader;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.NucleusPaths;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentManagerIntegTest extends BaseITCase {
    private Kernel kernel;

    @AfterEach
    void afterEach() {
        if (kernel != null) {
            kernel.shutdown();
        }
    }

    @Test
    void GIVEN_component_with_archived_artifact_WHEN_prepareArtifacts_THEN_unarchives_artifacts() throws Exception {
        // GIVEN
        kernel = new Kernel();

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
        when(mockDownloader.downloadToPath(any(), any(), any())).thenAnswer((i) -> {
            Files.copy(getClass().getResourceAsStream("zip.zip"), artifactFile.toPath());
            return artifactFile;
        });

        ComponentServiceHelper mockServiceHelper = mock(ComponentServiceHelper.class);


        String testRecipeContent =
                FileUtils.readFileToString(Paths.get(this.getClass().getResource("zip.yaml").toURI()).toFile());
        when(mockServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(testRecipeContent);
        kernel.getContext().put(ComponentServiceHelper.class, mockServiceHelper);

        // THEN
        kernel.getContext()
              .get(ComponentManager.class)
              .preparePackages(Collections.singletonList(ident))
              .get(10, TimeUnit.SECONDS);

        Path zipPath = nucleusPaths.unarchiveArtifactPath(ident, "zip");
        assertThat(zipPath.toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").resolve("1").toFile(), anExistingFile());
        assertThat(zipPath.resolve("zip").resolve("2").toFile(), anExistingFile());
    }
}
