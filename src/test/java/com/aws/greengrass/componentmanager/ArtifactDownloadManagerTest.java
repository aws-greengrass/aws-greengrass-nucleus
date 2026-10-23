/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager;

import com.amazon.aws.iot.greengrass.component.common.Unarchive;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.exceptions.SizeLimitException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.Permission;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.aws.greengrass.deployment.DeviceConfiguration.COMPONENT_STORE_MAX_SIZE_BYTES;
import static com.aws.greengrass.deployment.DeviceConfiguration.COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES;
import static com.aws.greengrass.deployment.DeviceConfiguration.MAX_PARALLEL_DOWNLOADS_TOPIC;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionUltimateCauseOfType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({GGExtension.class, MockitoExtension.class})
class ArtifactDownloadManagerTest {

    @Mock
    private ComponentStore componentStore;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private NucleusPaths nucleusPaths;
    @Mock
    private Unarchiver unarchiver;
    @Mock
    private ArtifactDownloader artifactDownloader;
    @Mock
    private Context context;

    @TempDir
    Path tempDir;

    private ArtifactDownloadManager artifactDownloadManager;
    private ComponentIdentifier componentIdentifier;

    @BeforeEach
    void beforeEach() throws Exception {
        Topic maxParallelDownloadsTopic = Topic.of(context, MAX_PARALLEL_DOWNLOADS_TOPIC, 1L);
        lenient().when(deviceConfiguration.getMaxParallelDownloads()).thenReturn(maxParallelDownloadsTopic);
        Topic maxSizeTopic = Topic.of(context, COMPONENT_STORE_MAX_SIZE_BYTES, COMPONENT_STORE_MAX_SIZE_DEFAULT_BYTES);
        lenient().when(deviceConfiguration.getComponentStoreMaxSizeBytes()).thenReturn(maxSizeTopic);

        lenient().when(artifactDownloader.downloadRequired()).thenReturn(true);
        lenient().when(artifactDownloader.checkDownloadable()).thenReturn(Optional.empty());
        lenient().when(artifactDownloader.checkComponentStoreSize()).thenReturn(true);
        lenient().when(artifactDownloader.canSetFilePermissions()).thenReturn(true);
        lenient().when(artifactDownloader.canUnarchiveArtifact()).thenReturn(true);
        lenient().when(componentStore.isDiskSpaceCritical()).thenReturn(false);

        componentIdentifier = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        artifactDownloadManager =
                new ArtifactDownloadManager(componentStore, deviceConfiguration, nucleusPaths, unarchiver);
    }

    private ArtifactDownloadManager.ArtifactDownloadTask newTask(ComponentArtifact artifact) {
        return new ArtifactDownloadManager.ArtifactDownloadTask(componentIdentifier, artifactDownloader, artifact);
    }

    @Test
    void GIVEN_empty_task_list_WHEN_invoke_download_tasks_THEN_do_nothing() throws Exception {
        artifactDownloadManager.invokeDownloadTasks(Collections.emptyList());

        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_artifact_already_downloaded_WHEN_invoke_download_tasks_THEN_do_not_download() throws Exception {
        when(artifactDownloader.downloadRequired()).thenReturn(false);
        ComponentArtifact artifact =
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary1")).build();

        artifactDownloadManager.invokeDownloadTasks(Collections.singletonList(newTask(artifact)));

        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_invoke_download_tasks_with_unarchive_THEN_calls_unarchiver()
            throws Exception {
        when(artifactDownloader.download()).thenReturn(new File("binary1"));
        when(artifactDownloader.getArtifactFile()).thenReturn(new File("binary1"));
        when(nucleusPaths.unarchiveArtifactPath(any(), any())).thenReturn(tempDir);

        ComponentArtifact artifact = ComponentArtifact.builder().artifactUri(new URI("greengrass:binary1"))
                .unarchive(Unarchive.ZIP).permission(Permission.builder().build()).build();

        artifactDownloadManager.invokeDownloadTasks(Collections.singletonList(newTask(artifact)));

        verify(unarchiver).unarchive(Unarchive.ZIP, new File("binary1"), tempDir);
    }

    @Test
    void GIVEN_disk_space_critical_WHEN_invoke_download_tasks_THEN_throws_execution_exception(
            ExtensionContext context) throws Exception {
        when(componentStore.isDiskSpaceCritical()).thenReturn(true);
        ComponentArtifact artifact =
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary1")).build();

        ignoreExceptionUltimateCauseOfType(context, SizeLimitException.class);
        ExecutionException e = assertThrows(ExecutionException.class, () -> artifactDownloadManager
                .invokeDownloadTasks(Collections.singletonList(newTask(artifact))));
        assertEquals(SizeLimitException.class, e.getCause().getClass());
        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_download_size_exceeds_remaining_store_space_WHEN_invoke_download_tasks_THEN_throws_size_limit_exception()
            throws Exception {
        // Configured max 1000 bytes; store already holds 900, leaving 100 free. A 200-byte download does not fit,
        // so the aggregate check must fail on the new download size pushing the total (1100) over the max (1000) --
        // not merely because existing content already exceeds the max.
        Topic maxSizeTopic = Topic.of(context, COMPONENT_STORE_MAX_SIZE_BYTES, 1000L);
        when(deviceConfiguration.getComponentStoreMaxSizeBytes()).thenReturn(maxSizeTopic);
        when(componentStore.getContentSize()).thenReturn(900L);
        when(artifactDownloader.getDownloadSize()).thenReturn(200L);
        ComponentArtifact artifact =
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary1")).build();

        assertThrows(SizeLimitException.class, () -> artifactDownloadManager
                .invokeDownloadTasks(Collections.singletonList(newTask(artifact))));
        verify(artifactDownloader, never()).download();
    }

    @Test
    void GIVEN_one_download_fails_WHEN_invoke_download_tasks_THEN_other_tasks_are_cancelled(
            ExtensionContext extensionContext) throws Exception {
        CountDownLatch slowDownloadStarted = new CountDownLatch(1);
        ArtifactDownloader failingDownloader = mock(ArtifactDownloader.class);
        lenient().when(failingDownloader.downloadRequired()).thenReturn(true);
        lenient().when(failingDownloader.checkDownloadable()).thenReturn(Optional.empty());
        lenient().when(failingDownloader.checkComponentStoreSize()).thenReturn(true);
        // Wait for the slow download to actually start before failing, so the fail-fast cancellation has a
        // real in-flight task to cancel rather than racing against it on wall-clock time.
        when(failingDownloader.download()).thenAnswer(invocation -> {
            slowDownloadStarted.await();
            throw new PackageDownloadException("boom");
        });

        ArtifactDownloader slowDownloader = mock(ArtifactDownloader.class);
        lenient().when(slowDownloader.downloadRequired()).thenReturn(true);
        lenient().when(slowDownloader.checkDownloadable()).thenReturn(Optional.empty());
        lenient().when(slowDownloader.checkComponentStoreSize()).thenReturn(true);
        when(slowDownloader.download()).thenAnswer(invocation -> {
            slowDownloadStarted.countDown();
            Thread.sleep(30_000);
            return new File("binary2");
        });

        List<ArtifactDownloadManager.ArtifactDownloadTask> tasks = new ArrayList<>();
        tasks.add(new ArtifactDownloadManager.ArtifactDownloadTask(componentIdentifier, failingDownloader,
                ComponentArtifact.builder().artifactUri(new URI("greengrass:failing")).build()));
        tasks.add(new ArtifactDownloadManager.ArtifactDownloadTask(componentIdentifier, slowDownloader,
                ComponentArtifact.builder().artifactUri(new URI("greengrass:slow")).build()));

        Topic maxParallelDownloadsTopic = Topic.of(context, MAX_PARALLEL_DOWNLOADS_TOPIC, 2L);
        lenient().when(deviceConfiguration.getMaxParallelDownloads()).thenReturn(maxParallelDownloadsTopic);
        artifactDownloadManager =
                new ArtifactDownloadManager(componentStore, deviceConfiguration, nucleusPaths, unarchiver);

        ignoreExceptionUltimateCauseOfType(extensionContext, PackageDownloadException.class);
        assertThrows(ExecutionException.class, () -> artifactDownloadManager.invokeDownloadTasks(tasks));
    }

    @Test
    void GIVEN_calling_thread_interrupted_mid_download_WHEN_invoke_download_tasks_THEN_propagates_interrupted_exception(
            ExtensionContext extensionContext) throws Exception {
        // invokeDownloadTasks logs the interrupt before propagating it; register it as expected so
        // GGExtension's log protector doesn't fail the test on that logged exception.
        ignoreExceptionOfType(extensionContext, InterruptedException.class);
        // A download that blocks until interrupted, modeling an external deployment cancellation/shutdown
        // arriving mid-download.
        CountDownLatch downloadStarted = new CountDownLatch(1);
        when(artifactDownloader.download()).thenAnswer(invocation -> {
            downloadStarted.countDown();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                // The pool worker running this mock download is interrupted by the fail-fast/cancel path;
                // restore the flag and return rather than letting the InterruptedException die uncaught on
                // the pool thread (which the test runner would otherwise report as a spurious error).
                Thread.currentThread().interrupt();
            }
            return new File("binary1");
        });
        ComponentArtifact artifact =
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary1")).build();

        // Run invokeDownloadTasks on a dedicated worker thread (rather than interrupting the JUnit test
        // thread itself) and capture what it throws, so we can assert the interrupt is surfaced as an
        // InterruptedException rather than swallowed into a normal return.
        AtomicReference<InterruptedException> interrupted = new AtomicReference<>();
        AtomicBoolean returnedNormally = new AtomicBoolean(false);
        Thread worker = new Thread(() -> {
            try {
                artifactDownloadManager.invokeDownloadTasks(Collections.singletonList(newTask(artifact)));
                returnedNormally.set(true);
            } catch (InterruptedException e) {
                interrupted.set(e);
            } catch (PackageLoadingException | PackageDownloadException | ExecutionException e) {
                // Any other outcome leaves both flags unset, failing the assertions below.
            }
        });
        worker.start();

        assertTrue(downloadStarted.await(10, TimeUnit.SECONDS), "download should have started");
        worker.interrupt();
        worker.join(10_000);

        assertFalse(returnedNormally.get(),
                "invokeDownloadTasks must not return normally when interrupted mid-download");
        assertNotNull(interrupted.get(), "interrupt should surface as InterruptedException");
    }
}
