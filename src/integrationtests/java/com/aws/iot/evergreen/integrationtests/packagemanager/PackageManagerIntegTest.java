/*
 * Copyright Amazon.com Inc. or its affiliates.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.integrationtests.packagemanager;

import com.aws.iot.evergreen.integrationtests.BaseITCase;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.GreengrassPackageServiceHelper;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.packagemanager.PackageStore;
import com.aws.iot.evergreen.packagemanager.models.PackageIdentifier;
import com.aws.iot.evergreen.packagemanager.plugins.GreengrassRepositoryDownloader;
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

class PackageManagerIntegTest extends BaseITCase {
    private Kernel kernel;

    @AfterEach
    void afterEach() throws Exception {
        kernel.shutdown();
    }

    @Test
    void GIVEN_component_with_archived_artifact_WHEN_prepareArtifacts_THEN_unarchives_artifacts() throws Exception {
        // GIVEN
        kernel = new Kernel();

        PackageIdentifier ident = new PackageIdentifier("A", new Semver("1.0.0"));

        PackageStore store = new PackageStore(tempRootDir);
        kernel.getContext().put(PackageStore.class, store);
        GreengrassRepositoryDownloader mockDownloader = mock(GreengrassRepositoryDownloader.class);
        kernel.getContext().put(GreengrassRepositoryDownloader.class, mockDownloader);
        when(mockDownloader.downloadToPath(any(), any(), any())).thenAnswer((i) -> {
            File p = store.resolveArtifactDirectoryPath(ident).resolve("zip.zip").toFile();
            Files.copy(getClass().getResourceAsStream("zip.zip"), p.toPath());
            return p;
        });

        GreengrassPackageServiceHelper mockServiceHelper = mock(GreengrassPackageServiceHelper.class);


        String testRecipeContent =
                FileUtils.readFileToString(Paths.get(this.getClass().getResource("zip.yaml").toURI()).toFile());
        when(mockServiceHelper.downloadPackageRecipeAsString(any())).thenReturn(testRecipeContent);
        kernel.getContext().put(GreengrassPackageServiceHelper.class, mockServiceHelper);

        // THEN
        kernel.getContext()
              .get(PackageManager.class)
              .preparePackages(Collections.singletonList(ident))
              .get(10, TimeUnit.SECONDS);

        Path zipPath = store.resolveAndSetupArtifactsUnpackDirectory(ident).resolve("zip");
        assertThat(zipPath.toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").resolve("1").toFile(), anExistingFile());
        assertThat(zipPath.resolve("zip").resolve("2").toFile(), anExistingFile());
    }
}
