/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.builtins.ArtifactDownloader;
import com.aws.greengrass.componentmanager.exceptions.InvalidArtifactUriException;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.ConnectionException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerLoginException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerServiceUnavailableException;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.util.CrashableSupplier;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.Utils;
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
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.AvoidCatchingGenericException",
        "PMD.AvoidInstanceofChecksInCatchClause", "PMD.AvoidRethrowingException"})
public class DockerImageDownloader extends ArtifactDownloader {
    static final String DOCKER_NOT_INSTALLED_ERROR_MESSAGE = "Docker engine is not installed. Install Docker and "
            + "retry the deployment.";

    private final EcrAccessor ecrAccessor;
    private final DefaultDockerClient dockerClient;
    private final MqttClient mqttClient;

    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig infiniteAttemptsRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMinutes(1L))
                    .maxRetryInterval(Duration.ofMinutes(64L)).maxAttempt(Integer.MAX_VALUE).retryableExceptions(
                    Arrays.asList(ConnectionException.class, SdkClientException.class, ServerException.class)).build();
    @Setter(AccessLevel.PACKAGE)
    private RetryUtils.RetryConfig finiteAttemptsRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofSeconds(10L))
                    .maxRetryInterval(Duration.ofMinutes(32L)).maxAttempt(30).retryableExceptions(
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
    public File download() throws PackageDownloadException, IOException, InterruptedException {
        logger.atDebug().log("Downloading artifact");
        checkDownloadPrerequisites();
        Image image;
        try {
            image = Image.fromArtifactUri(artifact);
        } catch (InvalidArtifactUriException e) {
            throw new PackageDownloadException("Failed to download due to bad artifact URI", e);
        }
        return performDownloadSteps(image);
    }

    @Override
    protected long download(long rangeStart, long rangeEnd, MessageDigest messageDigest)
            throws PackageDownloadException, InterruptedException {
        // N/A since handling partial download is managed by docker engine
        return 0;
    }

    @Override
    public boolean downloadRequired() throws PackageDownloadException {
        Image image;
        try {
            image = Image.fromArtifactUri(artifact);
        } catch (InvalidArtifactUriException e) {
            logger.atError().log(String.format("Unexpected error making the image object from the provided artifact %s",
                    artifact.getArtifactUri()));
            throw new PackageDownloadException("Failed to check if the download required due to bad artifact URI", e);
        }
        return !dockerClient.imageExistsLocally(image);
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

    private void checkDownloadPrerequisites() throws PackageDownloadException {
        // Check that Docker engine is installed
        if (!dockerClient.dockerInstalled()) {
            throw new PackageDownloadException(getErrorString(DOCKER_NOT_INSTALLED_ERROR_MESSAGE));
        }
    }

    private File performDownloadSteps(Image image) throws PackageDownloadException, InterruptedException {
        AtomicBoolean credentialRefreshNeeded = new AtomicBoolean(false);
        do {
            // Attempt getting credentials and login only for private registries, .
            if (image.getRegistry().isPrivateRegistry()) {
                // Get credentials for the registry
                try {
                    RetryUtils.runWithRetry(infiniteAttemptsRetryConfig, () -> {
                        // Currently we only support private registries in ECR so assume others are public,
                        // when we add support for private non-ECR registries, this can be expanded to retrieve
                        // credentials for them on case by case basis
                        if (image.getRegistry().isEcrRegistry()) {
                            // Get auth token for ECR, which represents ECR registry credentials

                            String imageHostedRegion = getRegionFromArtifactUri(image.getArtifactUri().toString());

                            Registry.Credentials credentials =
                                    ecrAccessor.getCredentials(image.getRegistry().getRegistryId(), imageHostedRegion);
                            image.getRegistry().setCredentials(credentials);
                            credentialRefreshNeeded.set(false);
                        }
                        return null;
                    }, "get-ecr-auth-token", logger);
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    throw new PackageDownloadException(getErrorString("Failed to get auth token for docker login"), e);
                }

                // Login to registry
                // TODO: [P44950158]: Avoid logging into registries which might already have been logged in previously
                //  with the same and valid credentials by maintaining a cache across artifacts and deployments
                run(() -> {
                    if (credentialsUsable(image)) {
                        dockerClient.login(image.getRegistry());
                    } else {
                        // Credentials have expired, re-fetch and login again
                        logger.atInfo().kv("registry-endpoint", image.getRegistry().getEndpoint())
                                .log("Registry credentials have expired,"
                                        + "fetching fresh credentials and logging in again");
                        credentialRefreshNeeded.set(true);
                    }
                    return null;
                }, "docker-login", "Failed to login to docker registry");
            }

            // Don't even attempt to pull images if credentials are expired, first try to refresh credentials
            if (credentialRefreshNeeded.get()) {
                continue;
            }

            // Docker pull
            run(() -> {
                if (credentialsUsable(image)) {
                    RetryUtils.runWithRetry(infiniteAttemptsRetryConfig, () -> {
                        dockerClient.pullImage(image);
                        return null;
                    }, "get-ecr-image", logger);
                } else {
                    // Credentials have expired, re-fetch and login again
                    logger.atInfo().kv("registry-endpoint", image.getRegistry().getEndpoint())
                            .log("Registry credentials have expired, fetching fresh credentials and logging in again");
                    credentialRefreshNeeded.set(true);
                }
                return null;
            }, "docker-pull-image", "Failed to download docker image");
        } while (credentialRefreshNeeded.get());
        // No file resources available since image artifacts are stored in docker's image store
        return null;
    }

    private String getRegionFromArtifactUri(String artifactUriStr) {
        //get the actual region from the artifact uri

        String regionStr = "";

        if (!Utils.isEmpty(artifactUriStr)) {
            String[] arr = artifactUriStr.split("\\.");

            if (arr.length > 1) {
                for (int i = 1; i < arr.length; i++) {
                    if ("amazonaws".equalsIgnoreCase(arr[i])) {
                        regionStr = arr[i - 1];
                        break;
                    }
                }
            }
        }

        return regionStr;
    }

    /*
     * Check if credentials are valid for private registries, always return true for public registries.
     */
    private boolean credentialsUsable(Image image) {
        return !image.getRegistry().isPrivateRegistry() || image.getRegistry().getCredentials().isValid();
    }

    private <T> void run(CrashableSupplier<T, Exception> task, String description, String message)
            throws PackageDownloadException, InterruptedException {
        try {
            // Finite retry attempts for errors that are not due to connectivity issues and
            // might need explicit intervention to recover from
            RetryUtils.runWithRetry(finiteAttemptsRetryConfig, () -> RetryUtils
                    // Indefinite retry for errors that are due to connectivity issues and can be
                    // resolved when connectivity comes back
                    .runWithRetry(infiniteAttemptsRetryConfig, () -> runWithConnectionErrorCheck(task), description,
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
                throw new ConnectionException("Device appears to be offline, should retry the task", e);
            }
            throw e;
        }
    }
}
