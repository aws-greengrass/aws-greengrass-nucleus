/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.util.FileSystemPermission;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.platforms.Platform;
import com.vdurmont.semver4j.Semver;
import org.apache.commons.lang3.SystemUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.aws.greengrass.testcommons.testutilities.Matchers.hasPermission;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.io.FileMatchers.anExistingDirectory;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComponentManagerIntegTest extends BaseITCase {
    private Kernel kernel;
    private final PlatformResolver platformResolver = new PlatformResolver(null);
    private final RecipeLoader recipeLoader = new RecipeLoader(platformResolver);

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
        ComponentIdentifier ident = new ComponentIdentifier("aws.iot.gg.test.integ.zip", new Semver("1.0.0"));

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);
        kernel.getContext().put(ComponentStore.class, store);

        ArtifactDownloader mockDownloader = mock(ArtifactDownloader.class);
        File artifactFile = store.resolveArtifactDirectoryPath(ident).resolve("zip.zip").toFile();
        when(mockDownloader.downloadRequired()).thenReturn(true);
        when(mockDownloader.checkDownloadable()).thenReturn(Optional.empty());
        when(mockDownloader.getArtifactFile()).thenReturn(artifactFile);
        when(mockDownloader.canUnarchiveArtifact()).thenReturn(true);
        when(mockDownloader.canSetFilePermissions()).thenReturn(true);
        when(mockDownloader.checkComponentStoreSize()).thenReturn(true);
        when(mockDownloader.download()).thenAnswer(downloadToPath("zip.zip", artifactFile));

        ArtifactDownloaderFactory mockDownloaderFactory = mock(ArtifactDownloaderFactory.class);
        when(mockDownloaderFactory.getArtifactDownloader(any(), any(), any())).thenReturn(mockDownloader);

        kernel.getContext().put(ArtifactDownloaderFactory.class, mockDownloaderFactory);

        Files.copy(Paths.get(this.getClass().getResource("aws.iot.gg.test.integ.zip-1.0.0.yaml").toURI()),
                nucleusPaths.recipePath().resolve(PreloadComponentStoreHelper
                        .getRecipeStorageFilenameFromTestSource("aws.iot.gg.test.integ.zip-1.0.0.yaml")));

        // THEN
        kernel.getContext().get(ComponentManager.class).preparePackages(Collections.singletonList(ident))
                .get(10, TimeUnit.SECONDS);

        Path zipPath = nucleusPaths.unarchiveArtifactPath(ident, "zip");
        assertThat(zipPath.toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").toFile(), anExistingDirectory());
        assertThat(zipPath.resolve("zip").resolve("1").toFile(), anExistingFile());
        assertThat(zipPath.resolve("zip").resolve("2").toFile(), anExistingFile());

        // check everyone can enter dir
        assertThat(zipPath.resolve("zip"), hasPermission(
                FileSystemPermission.builder().ownerRead(true).ownerWrite(true).ownerExecute(true).groupRead(true)
                        .groupExecute(true).otherRead(true).otherExecute(true).build()));

        // check perms match what we gave
        FileSystemPermission allRead = FileSystemPermission.builder().ownerRead(true).groupRead(true).otherRead(true)
                .ownerWrite(!PlatformResolver.isWindows && !SystemUtils.USER_NAME.equals(ROOT))
                // we preserve write permissions for non-root user on non-windows platforms
                .build();

        assertThat(zipPath.resolve("zip").resolve("1"), hasPermission(allRead));
        assertThat(zipPath.resolve("zip").resolve("2"), hasPermission(allRead));
    }

    @Test
    void GIVEN_component_with_artifact_WHEN_prepareArtifacts_THEN_set_permissions_on_artifacts() throws Exception {
        ComponentIdentifier ident = new ComponentIdentifier("aws.iot.gg.test.integ.perm", new Semver("1.0.0"));

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);
        kernel.getContext().put(ComponentStore.class, store);
        File scriptFile = store.resolveArtifactDirectoryPath(ident).resolve("script.sh").toFile();
        File emptyFile = store.resolveArtifactDirectoryPath(ident).resolve("empty.txt").toFile();
        ArtifactDownloader mockDownloader = mock(ArtifactDownloader.class);
        when(mockDownloader.downloadRequired()).thenReturn(true);
        when(mockDownloader.checkDownloadable()).thenReturn(Optional.empty());
        when(mockDownloader.getArtifactFile()).thenReturn(scriptFile).thenReturn(emptyFile);
        when(mockDownloader.canUnarchiveArtifact()).thenReturn(true);
        when(mockDownloader.canSetFilePermissions()).thenReturn(true);
        when(mockDownloader.checkComponentStoreSize()).thenReturn(true);
        when(mockDownloader.download()).thenAnswer(downloadToPath("script.sh", scriptFile))
                .thenAnswer(downloadToPath("empty.txt", emptyFile));

        ArtifactDownloaderFactory mockDownloaderFactory = mock(ArtifactDownloaderFactory.class);
        when(mockDownloaderFactory.getArtifactDownloader(any(), any(), any())).thenReturn(mockDownloader);
        kernel.getContext().put(ArtifactDownloaderFactory.class, mockDownloaderFactory);

        Files.copy(Paths.get(this.getClass().getResource("aws.iot.gg.test.integ.perm-1.0.0.yaml").toURI()),
                nucleusPaths.recipePath().resolve(PreloadComponentStoreHelper
                        .getRecipeStorageFilenameFromTestSource("aws.iot.gg.test.integ.perm-1.0.0.yaml")));
        // THEN
        kernel.getContext().get(ComponentManager.class).preparePackages(Collections.singletonList(ident))
                .get(10, TimeUnit.SECONDS);
        assertThat(nucleusPaths.artifactPath(ident).resolve("script.sh"), hasPermission(
                FileSystemPermission.builder().ownerRead(true).groupRead(true).otherRead(true)
                        .ownerWrite(!PlatformResolver.isWindows && !SystemUtils.USER_NAME.equals(ROOT))
                        // we preserve write permissions for non-root user on non-windows platforms
                        .ownerExecute(true).groupExecute(true).build()));

        assertThat(nucleusPaths.artifactPath(ident).resolve("empty.txt"), hasPermission(
                FileSystemPermission.builder().ownerRead(true).groupRead(true)
                        .ownerWrite(!PlatformResolver.isWindows && !SystemUtils.USER_NAME.equals(ROOT))
                        // we preserve write permissions for non-root user on non-windows platforms
                        .build()));
    }

    /**
     * Mock download a file to a path using a class resource.
     *
     * @param resource the resource to copy
     * @param f        the file location to copy to.
     * @return an answer that can be used for mocking.
     */
    Answer<File> downloadToPath(String resource, File f) {
        return (i) -> {
            Files.copy(getClass().getResourceAsStream(resource), f.toPath());
            return f;
        };
    }

    @Test
    void GIVEN_multiple_artifacts_and_max_parallel_downloads_configured_WHEN_prepareArtifacts_THEN_downloads_run_concurrently()
            throws Exception {
        ComponentIdentifier ident = new ComponentIdentifier("aws.iot.gg.test.integ.parallel", new Semver("1.0.0"));

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);
        kernel.getContext().put(ComponentStore.class, store);

        // Configure the device to allow all 3 artifacts to download at once.
        DeviceConfiguration deviceConfiguration = kernel.getContext().get(DeviceConfiguration.class);
        deviceConfiguration.getMaxParallelDownloads().withValue(3L);

        // Each mocked download blocks until all 3 downloads have started. If downloads were still
        // sequential (today's pre-parallel behavior), the second download() call would never happen --
        // it would be waiting on the first, which itself is waiting for all 3 to have started -- so this
        // test would time out instead of passing if concurrency regressed to sequential.
        List<File> artifactFiles = new ArrayList<>();
        for (String name : new String[] {"artifact1.txt", "artifact2.txt", "artifact3.txt"}) {
            artifactFiles.add(store.resolveArtifactDirectoryPath(ident).resolve(name).toFile());
        }
        CountDownLatch allDownloadsStarted = new CountDownLatch(3);
        AtomicInteger concurrentDownloads = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);

        ArtifactDownloader mockDownloader = mock(ArtifactDownloader.class);
        when(mockDownloader.downloadRequired()).thenReturn(true);
        when(mockDownloader.checkDownloadable()).thenReturn(Optional.empty());
        when(mockDownloader.canUnarchiveArtifact()).thenReturn(false);
        when(mockDownloader.canSetFilePermissions()).thenReturn(false);
        when(mockDownloader.checkComponentStoreSize()).thenReturn(true);
        when(mockDownloader.download()).thenAnswer((invocation) -> {
            int current = concurrentDownloads.incrementAndGet();
            maxObservedConcurrency.getAndUpdate(prevMax -> Math.max(prevMax, current));
            allDownloadsStarted.countDown();
            assertTrue(allDownloadsStarted.await(10, TimeUnit.SECONDS),
                    "All 3 downloads should have started concurrently within the timeout");
            concurrentDownloads.decrementAndGet();
            return artifactFiles.get(0);
        });

        ArtifactDownloaderFactory mockDownloaderFactory = mock(ArtifactDownloaderFactory.class);
        when(mockDownloaderFactory.getArtifactDownloader(any(), any(), any())).thenReturn(mockDownloader);
        kernel.getContext().put(ArtifactDownloaderFactory.class, mockDownloaderFactory);

        Files.copy(Paths.get(this.getClass().getResource("aws.iot.gg.test.integ.parallel-1.0.0.yaml").toURI()),
                nucleusPaths.recipePath().resolve(PreloadComponentStoreHelper
                        .getRecipeStorageFilenameFromTestSource("aws.iot.gg.test.integ.parallel-1.0.0.yaml")));

        kernel.getContext().get(ComponentManager.class).preparePackages(Collections.singletonList(ident))
                .get(15, TimeUnit.SECONDS);

        assertThat(maxObservedConcurrency.get(), is(3));
    }
}
