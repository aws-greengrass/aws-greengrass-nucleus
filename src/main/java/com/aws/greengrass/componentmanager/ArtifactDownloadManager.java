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
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Permissions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import javax.inject.Inject;

/**
 * Downloads artifacts for a batch of components, honoring the configured parallelism
 * (device configuration key {@code maxParallelDownloads}, default 1). All download tasks are
 * invoked against a shared, global pool -- concurrency is not scoped per-component. If any one
 * download task fails, every other in-flight or queued download task is cancelled immediately,
 * preserving today's all-or-nothing deployment failure semantics.
 */
public class ArtifactDownloadManager {
    private static final Logger logger = LogManager.getLogger(ArtifactDownloadManager.class);

    private final ComponentStore componentStore;
    private final DeviceConfiguration deviceConfiguration;
    private final NucleusPaths nucleusPaths;
    private final Unarchiver unarchiver;
    private int poolSize;

    /**
     * Constructor.
     *
     * @param componentStore      component store used for disk-space checks and artifact directory resolution
     * @param deviceConfiguration device configuration, providing the configured download parallelism
     * @param nucleusPaths        nucleus paths, used to resolve unarchive destination paths
     * @param unarchiver          unarchiver used to expand downloaded artifacts that require it
     */
    @Inject
    public ArtifactDownloadManager(ComponentStore componentStore, DeviceConfiguration deviceConfiguration,
                                    NucleusPaths nucleusPaths, Unarchiver unarchiver) {
        this.componentStore = componentStore;
        this.deviceConfiguration = deviceConfiguration;
        this.nucleusPaths = nucleusPaths;
        this.unarchiver = unarchiver;
        deviceConfiguration.getMaxParallelDownloads().subscribe((what, topic) ->
                this.poolSize = Coerce.toInt(deviceConfiguration.getMaxParallelDownloads()));
    }

    /**
     * Invoke every given download task, honoring the configured parallelism, cancelling every other
     * in-flight/queued task as soon as any one fails. If interrupted, cancels everything, restores the
     * thread's interrupt status, and returns -- the caller is always the origin of an interrupt here,
     * so nothing new needs to be reported back via an exception.
     *
     * @param downloadTasks each unit of work: which component, which downloader, which artifact metadata
     * @throws SizeLimitException        if the aggregate download would exceed the configured component store size,
     *                                    or if usable disk space is critically low
     * @throws ExecutionException        wrapping whatever a failed download task threw; caller decides how to
     *                                    handle it
     * @throws PackageLoadingException   if unable to access the package store while checking its size
     * @throws PackageDownloadException  if unable to determine an artifact's download size while checking the
     *                                   aggregate download size
     */
    public void invokeDownloadTasks(List<ArtifactDownloadTask> downloadTasks)
            throws SizeLimitException, ExecutionException, PackageLoadingException, PackageDownloadException {
        try {
            checkAggregateDownloadSize(downloadTasks.stream()
                    .filter(t -> t.downloader.checkComponentStoreSize())
                    .map(t -> t.downloader)
                    .collect(Collectors.toList()));
        } catch (InterruptedException ie) {
            logger.atInfo().setCause(ie).log("Interrupted while checking aggregate download size.");
            Thread.currentThread().interrupt();
            return;
        }

        ExecutorService downloadPool = Executors.newFixedThreadPool(poolSize);
        try {
            CompletionService<Void> completionService = new ExecutorCompletionService<>(downloadPool);
            List<Future<Void>> submittedFutures = new ArrayList<>();
            for (ArtifactDownloadTask downloadTask : downloadTasks) {
                submittedFutures.add(completionService.submit(() -> invokeDownloadTask(downloadTask)));
            }

            try {
                int remaining = downloadTasks.size();
                while (remaining > 0) {
                    completionService.take().get();
                    remaining--;
                }
            } catch (ExecutionException e) {
                submittedFutures.forEach(f -> f.cancel(true));
                throw e;
            } catch (InterruptedException ie) {
                submittedFutures.forEach(f -> f.cancel(true));
                logger.atInfo().setCause(ie).log("Interrupted while downloading artifacts.");
                Thread.currentThread().interrupt();
            }
        } finally {
            downloadPool.shutdownNow();
        }
    }

    private void checkAggregateDownloadSize(List<ArtifactDownloader> downloadersNeedingSizeCheck)
            throws PackageDownloadException, InterruptedException, PackageLoadingException {
        long totalDownloadSize = 0;
        for (ArtifactDownloader downloader : downloadersNeedingSizeCheck) {
            totalDownloadSize += downloader.getDownloadSize();
        }
        long storeContentSize = componentStore.getContentSize();
        long configuredMaxSize = Coerce.toLong(deviceConfiguration.getComponentStoreMaxSizeBytes());
        if (storeContentSize + totalDownloadSize > configuredMaxSize) {
            throw new SizeLimitException(String.format(
                    "Component store size limit reached: %d bytes existing, %d bytes needed"
                            + ", %d bytes maximum allowed total", storeContentSize, totalDownloadSize,
                    configuredMaxSize));
        }
    }

    private Void invokeDownloadTask(ArtifactDownloadTask downloadTask)
            throws PackageDownloadException, InterruptedException, IOException, PackageLoadingException {
        ArtifactDownloader downloader = downloadTask.downloader;
        ComponentArtifact artifact = downloadTask.artifact;

        if (downloader.downloadRequired()) {
            Optional<String> errorMsg = downloader.checkDownloadable();
            if (errorMsg.isPresent()) {
                throw new PackageDownloadException(
                        String.format("Download required for artifact %s but device configs are invalid: %s",
                                artifact.getArtifactUri(), errorMsg.get()),
                        DeploymentErrorCode.DEVICE_CONFIG_NOT_VALID_FOR_ARTIFACT_DOWNLOAD);
            }
            if (componentStore.isDiskSpaceCritical()) {
                throw new SizeLimitException("Disk space critical, minimum allowed not met");
            }
            downloader.download();
        } else {
            logger.atDebug().log("Artifact download is not required for [{}]", artifact.getArtifactUri());
        }
        if (downloader.canSetFilePermissions()) {
            File artifactFile = downloader.getArtifactFile();
            if (artifactFile != null) {
                Permissions.setArtifactPermission(artifactFile.toPath(),
                        artifact.getPermission().toFileSystemPermission());
            }
        }
        if (downloader.canUnarchiveArtifact()) {
            Unarchive unarchive = artifact.getUnarchive();
            if (unarchive == null) {
                unarchive = Unarchive.NONE;
            }
            File artifactFile = downloader.getArtifactFile();
            if (artifactFile != null && !unarchive.equals(Unarchive.NONE)) {
                ComponentIdentifier componentIdentifier = downloadTask.componentIdentifier;
                Path unarchivePath = nucleusPaths.unarchiveArtifactPath(componentIdentifier,
                        getFileName(artifactFile));
                unarchiver.unarchive(unarchive, artifactFile, unarchivePath);
                if (downloader.canSetFilePermissions()) {
                    Permissions.setArtifactPermission(unarchivePath, artifact.getPermission()
                            .toFileSystemPermission());
                }
            }
        }
        return null;
    }

    private static String getFileName(File f) {
        String fileName = f.getName();
        return fileName.indexOf('.') > 0 ? fileName.substring(0, fileName.lastIndexOf('.')) : fileName;
    }

    /**
     * One unit of download work: which component the artifact belongs to, the downloader responsible for
     * fetching it, and the artifact's own metadata (permissions, unarchive setting, URI).
     */
    public static final class ArtifactDownloadTask {
        private final ComponentIdentifier componentIdentifier;
        private final ArtifactDownloader downloader;
        private final ComponentArtifact artifact;

        /**
         * Constructor.
         *
         * @param componentIdentifier the component this artifact belongs to
         * @param downloader          the downloader responsible for fetching this artifact
         * @param artifact            this artifact's metadata (URI, permissions, unarchive setting)
         */
        public ArtifactDownloadTask(ComponentIdentifier componentIdentifier, ArtifactDownloader downloader,
                                     ComponentArtifact artifact) {
            this.componentIdentifier = componentIdentifier;
            this.downloader = downloader;
            this.artifact = artifact;
        }
    }
}
