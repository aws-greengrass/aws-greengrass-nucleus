/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.exceptions.ConnectionException;
import com.aws.greengrass.componentmanager.plugins.exceptions.DockerLoginException;
import com.aws.greengrass.componentmanager.plugins.exceptions.DockerServiceUnavailableException;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.util.CrashableSupplier;
import com.aws.greengrass.util.RetryUtils;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecr.model.ServerException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidCatchingGenericException",
        "PMD.AvoidInstanceofChecksInCatchClause", "PMD.AvoidRethrowingException"})
public class DockerImageDownloader extends ArtifactDownloader {

    private final EcrAccessor ecrAccessor;
    private final DefaultDockerClient dockerClient;
    private final MqttClient mqttClient;

    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig networkIssuesRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(Integer.MAX_VALUE).retryableExceptions(
                    Arrays.asList(ConnectionException.class, SdkClientException.class, ServerException.class)).build();
    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig nonNetworkIssuesRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(1L)).maxAttempt(30).retryableExceptions(
                    Arrays.asList(DockerServiceUnavailableException.class, DockerLoginException.class,
                            SdkClientException.class, ServerException.class)).build();

    /**
     * Constructor.
     *
     * @param identifier  component identifier
     * @param artifact    artifact to download
     * @param artifactDir artifact store path
     * @param context     context
     */
    public DockerImageDownloader(ComponentIdentifier identifier, ComponentArtifact artifact, Path artifactDir,
                                 Context context) {
        super(identifier, artifact, artifactDir);
        ecrAccessor = context.get(EcrAccessor.class);
        dockerClient = context.get(DefaultDockerClient.class);
        mqttClient = context.get(MqttClient.class);
    }

    DockerImageDownloader(ComponentIdentifier identifier, ComponentArtifact artifact, Path artifactDir,
                          DefaultDockerClient dockerClient, EcrAccessor ecrAccessor, MqttClient mqttClient) {
        super(identifier, artifact, artifactDir);
        this.dockerClient = dockerClient;
        this.ecrAccessor = ecrAccessor;
        this.mqttClient = mqttClient;
    }

    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException {
        // N/A since handling partial download is managed by docker engine
        return 0;
    }

    @Override
    public File download() throws PackageDownloadException, IOException, InterruptedException {
        // Check that Docker engine is installed
        if (!dockerClient.dockerInstalled()) {
            throw new PackageDownloadException("Docker engine is not installed on the device, please ensure it's "
                    + "installed and redo the deployment");
        }

        Image image = Image.fromArtifactUri(artifact.getArtifactUri());
        if (image.getRegistry().isEcrRegistry() && image.getRegistry().isPrivateRegistry()) {
            // Get auth token for ECR
            try {
                RetryUtils.runWithRetry(networkIssuesRetryConfig, () -> {
                    Registry.Credentials credentials = ecrAccessor.getCredentials(image.getRegistry().getRegistryId());
                    image.getRegistry().setCredentials(credentials);
                    return null;
                }, "get-ecr-auth-token", logger);
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                throw new PackageDownloadException(getErrorString("Failed to get auth token for docker login"), e);
            }

            // Login to registry
            run(() -> dockerClient.login(image.getRegistry()), "docker-login", "Failed to login to docker registry");
        }

        // Docker pull
        // TODO : Redo credential fetching and login if ECR registry credentials expire by the time pull is attempted
        run(() -> dockerClient.pullImage(image), "docker-pull-image", "Failed to download docker image");

        // No file resources available since image artifacts are stored in docker's image store
        return null;
    }

    private <T> void run(CrashableSupplier<T, Exception> task, String description, String message)
            throws PackageDownloadException, InterruptedException {
        try {
            // Retry with relevant config for errors that are not due to connectivity issues
            RetryUtils.runWithRetry(nonNetworkIssuesRetryConfig, () -> RetryUtils
                    // Retry with relevant config for errors that are due to connectivity issues
                    .runWithRetry(networkIssuesRetryConfig, () -> runWithConnectionErrorCheck(task), description,
                            logger), description, logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageDownloadException(getErrorString(message), e);
        }
    }

    private <T> T runWithConnectionErrorCheck(CrashableSupplier<T, Exception> task) throws Exception {
        try {
            return task.apply();
        } catch (Exception e) {
            // Since docker engine can throw service unavailable error that may or may not indicate a problem that
            // can be fixed with retries, explicitly check if an error could be due to connectivity problem
            // we infer that based on Mqtt connection, even though not perfect, it should accurately represent if
            // device is having connectivity issues most of the times.
            if (e instanceof DockerServiceUnavailableException && !mqttClient.getMqttOnline().get()) {
                throw new ConnectionException(String.format("Device appears to be offline, should retry the task "), e);
            }
            throw e;
        }
    }

    @Override
    public boolean downloadRequired() {
        // TODO : Consider executing `docker image ls` to see if the required image version(tag/digest) already
        //  exists to save a download attempt
        return true;
    }

    @Override
    public Optional<String> checkDownloadable() {
        // TODO : Maybe worth checking if device is configured such that it can get TES credentials for ECR.
        // N/A for images from other registries
        return Optional.empty();
    }

    @Override
    public Long getDownloadSize() throws PackageDownloadException, InterruptedException {
        // Not supported for docker images
        return null;
    }

    @Override
    public String getArtifactFilename() {
        // Not applicable for docker images since docker engine abstracts this
        return null;
    }

    @Override
    public boolean checkComponentStoreSize() {
        // Not applicable for docker images since docker has its own image store
        return false;
    }

    @Override
    public boolean canSetFilePermissions() {
        // Not applicable for docker images since docker has its own image store
        return false;
    }

    @Override
    public boolean canUnarchiveArtifact() {
        // Not applicable for docker images since docker engine abstracts this
        return false;
    }
}
