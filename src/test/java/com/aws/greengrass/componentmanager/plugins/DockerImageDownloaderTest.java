/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.exceptions.ConnectionException;
import com.aws.greengrass.componentmanager.plugins.exceptions.DockerLoginException;
import com.aws.greengrass.componentmanager.plugins.exceptions.DockerServiceUnavailableException;
import com.aws.greengrass.componentmanager.plugins.exceptions.InvalidImageOrAccessDeniedException;
import com.aws.greengrass.componentmanager.plugins.exceptions.RegistryAuthException;
import com.aws.greengrass.componentmanager.plugins.exceptions.UserNotAuthorizedForDockerException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.RetryUtils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecr.model.ServerException;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DockerImageDownloaderTest {
    private static ComponentIdentifier TEST_COMPONENT_ID =
            new ComponentIdentifier("test.container.component", new Semver("1.0.0"));
    // Using distinct retry attempts to use as means of verification
    private final RetryUtils.RetryConfig networkIssuesRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMillis(50L))
                    .maxRetryInterval(Duration.ofMillis(50L)).maxAttempt(5).retryableExceptions(
                    Arrays.asList(ConnectionException.class, SdkClientException.class, ServerException.class)).build();
    private final RetryUtils.RetryConfig nonNetworkIssuesRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMillis(50L))
                    .maxRetryInterval(Duration.ofMillis(50L)).maxAttempt(2).retryableExceptions(
                    Arrays.asList(DockerServiceUnavailableException.class, DockerLoginException.class,
                            SdkClientException.class, ServerException.class)).build();

    @Mock
    private DefaultDockerClient dockerClient;
    @Mock
    private EcrAccessor ecrAccessor;
    @Mock
    private MqttClient mqttClient;
    @Mock
    private Path artifactDir;

    @BeforeEach
    public void setup() throws Exception {
        // AtomicBoolean mqttOnline = new AtomicBoolean(true);
        lenient().when(mqttClient.getMqttOnline()).thenReturn(new AtomicBoolean(true));
    }

    @Test
    void GIVEN_a_container_component_with_an_ecr_image_with_digest_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri =
                new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage@sha256:5442792a-752c-11eb-9439-0242ac130002");
        Image image = Image.fromArtifactUri(artifactUri);

        when(ecrAccessor.getCredentials("012345678910"))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().plusSeconds(60)));
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.login(image.getRegistry())).thenReturn(true);
        when(dockerClient.pullImage(image)).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("testimage", image.getName());
        assertEquals("sha256:5442792a-752c-11eb-9439-0242ac130002", image.getDigest());
        assertNull(image.getTag());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals("012345678910.dkr.ecr.us-east-1.amazonaws.com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        verify(ecrAccessor).getCredentials("012345678910");
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_an_ecr_image_with_tag_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(ecrAccessor.getCredentials("012345678910"))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().plusSeconds(60)));
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.login(image.getRegistry())).thenReturn(true);
        when(dockerClient.pullImage(image)).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals("012345678910.dkr.ecr.us-east-1.amazonaws.com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        verify(ecrAccessor).getCredentials("012345678910");
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_a_public_ecr_image_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri = new URI("docker:public.ecr.aws/a1b2c3d4/testimage:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.pullImage(image)).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("public.ecr.aws/a1b2c3d4", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient).pullImage(image);
        verify(dockerClient, never()).login(any());
    }

    @Test
    void GIVEN_a_container_component_with_a_public_dockerhub_image_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.pullImage(image)).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_in_deployment_WHEN_docker_not_installed_THEN_fail_deployment() throws Exception {
        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(false);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Docker engine is not installed on the device"));

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient, never()).pullImage(any());
    }

    @Test
    void GIVEN_a_container_component_with_image_in_dockerhub_WHEN_image_not_public_THEN_fail_deployment()
            throws Exception {
        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.pullImage(image)).thenThrow(new InvalidImageOrAccessDeniedException(
                "Invalid image or login - repository does not exist or may require 'docker login'"));

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to download docker image"));
        assertTrue(err.getCause() instanceof InvalidImageOrAccessDeniedException);

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_image_in_ecr_WHEN_when_failed_to_get_credentials_THEN_fail_deployment()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(ecrAccessor.getCredentials("012345678910"))
                .thenThrow(new RegistryAuthException("Failed to get " + "credentials for ECR registry"));
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to get auth token for docker login"));
        assertTrue(err.getCause() instanceof RegistryAuthException);

        assertEquals("testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals("012345678910.dkr.ecr.us-east-1.amazonaws.com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        verify(ecrAccessor).getCredentials("012345678910");
        verify(dockerClient, never()).login(any());
        verify(dockerClient, never()).pullImage(any());
    }

    @Test
    void GIVEN_a_container_component_WHENn_failed_to_pull_image_THEN_fail_deployment(ExtensionContext extensionContext)
            throws Exception {
        ignoreExceptionOfType(extensionContext, DockerServiceUnavailableException.class);

        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        // fail all retries
        when(dockerClient.pullImage(image)).thenThrow(new DockerServiceUnavailableException("Service Unavailable"));

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to download docker image"));
        assertTrue(err.getCause() instanceof DockerServiceUnavailableException);

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        // Invocations as many as the retry count should be expected
        verify(dockerClient, times(2)).pullImage(any());
    }

    @Test
    void GIVEN_connectivity_missing_WHEN_failed_to_pull_and_connectivity_is_back_THEN_retry_and_succeed(
            ExtensionContext extensionContext) throws Exception {
        ignoreExceptionOfType(extensionContext, DockerServiceUnavailableException.class);
        ignoreExceptionOfType(extensionContext, ConnectionException.class);

        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(mqttClient.getMqttOnline()).thenReturn(new AtomicBoolean(false));
        // Fail on first 3 attempts, succeed on fourth attempt to simulate that pull would succeed when connectivity
        // comes back
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.pullImage(image)).thenThrow(new DockerServiceUnavailableException("Service Unavailable"))
                .thenThrow(new DockerServiceUnavailableException("Service Unavailable"))
                .thenThrow(new DockerServiceUnavailableException("Service Unavailable"))
                .thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        // Invocations as many as the retry attempts should be expected
        verify(dockerClient, times(4)).pullImage(any());
        verify(mqttClient, times(3)).getMqttOnline();
    }

    @Test
    void GIVEN_connectivity_available_WHEN_failed_to_pull_image_intermittently_THEN_succeed_on_retries(
            ExtensionContext extensionContext) throws Exception {
        ignoreExceptionOfType(extensionContext, DockerServiceUnavailableException.class);

        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        // fail first attempt, succeed on next retry attempt, device is connected the whole time but there could be
        // temporary issues with docker cloud which can get resolved in a short span
        when(dockerClient.pullImage(image)).thenThrow(new DockerServiceUnavailableException("Service Unavailable"))
                .thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        // Invocations as many as the retry count should be expected
        verify(dockerClient, times(2)).pullImage(any());
        // Connectivity will be checked on failures which is only once for this test
        verify(mqttClient, times(1)).getMqttOnline();
    }

    @Test
    void GIVEN_connectivity_available_WHEN_failed_to_pull_image_consistently_THEN_fail_after_finite_retries(
            ExtensionContext extensionContext) throws Exception {
        ignoreExceptionOfType(extensionContext, DockerServiceUnavailableException.class);

        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        // fail first attempt, succeed on next retry attempt, device is connected the whole time
        when(dockerClient.pullImage(image)).thenThrow(new DockerServiceUnavailableException("Service Unavailable"));

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to download docker image"));
        assertTrue(err.getCause() instanceof DockerServiceUnavailableException);

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        // Invocations as many as the retry count should be expected
        verify(dockerClient, times(2)).pullImage(any());
        verify(mqttClient, times(2)).getMqttOnline();
    }

    @Test
    void GIVEN_a_container_component_WHEN_greengrass_does_not_have_permissions_to_use_docker_daemon_THEN_fail_deployment()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.login(any())).thenThrow(new UserNotAuthorizedForDockerException(
                "Got permission denied while trying to connect to the Docker daemon socket"));

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to login to docker registry"));
        assertTrue(err.getCause() instanceof UserNotAuthorizedForDockerException);

        assertEquals("testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals("012345678910.dkr.ecr.us-east-1.amazonaws.com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        verify(ecrAccessor).getCredentials("012345678910");
        verify(dockerClient).login(any());
        verify(dockerClient, never()).pullImage(any());
    }

    @Test
    void GIVEN_a_container_component_with_no_registry_in_uri_WHEN_deployed_THEN_download_image_artifact_from_dockerhub()
            throws Exception {
        URI artifactUri = new URI("docker:alpine:sometag");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.pullImage(image)).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_no_digest_or_tag_in_uri_WHEN_deployed_THEN_assume_latest_image_version()
            throws Exception {
        URI artifactUri = new URI("docker:alpine");
        Image image = Image.fromArtifactUri(artifactUri);
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(dockerClient.pullImage(image)).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("latest", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient).pullImage(image);
    }

    private DockerImageDownloader getDownloader(URI artifactUri) {
        DockerImageDownloader downloader = new DockerImageDownloader(TEST_COMPONENT_ID,
                ComponentArtifact.builder().artifactUri(artifactUri).build(), artifactDir, dockerClient, ecrAccessor,
                mqttClient);
        downloader.setNetworkIssuesRetryConfig(networkIssuesRetryConfig);
        downloader.setNonNetworkIssuesRetryConfig(nonNetworkIssuesRetryConfig);
        return downloader;
    }
}
