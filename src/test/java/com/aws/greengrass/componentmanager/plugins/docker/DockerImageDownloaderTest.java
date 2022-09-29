/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins.docker;

import com.amazon.aws.iot.greengrass.component.common.RecipeFormatVersion;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.ComponentRecipe;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.ConnectionException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerLoginException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerServiceUnavailableException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.InvalidImageOrAccessDeniedException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.RegistryAuthException;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.UserNotAuthorizedForDockerException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.RetryUtils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ecr.model.ServerException;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.aws.greengrass.componentmanager.plugins.docker.DockerImageDownloader.DOCKER_NOT_INSTALLED_ERROR_MESSAGE;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DockerImageDownloaderTest {
    private static ComponentIdentifier TEST_COMPONENT_ID =
            new ComponentIdentifier("test.container.component", new Semver("1.0.0"));

    // Using retry config with much smaller interval and count
    private final RetryUtils.RetryConfig infiniteAttemptsRetryConfig =
            RetryUtils.RetryConfig.builder().initialRetryInterval(Duration.ofMillis(50L))
                    .maxRetryInterval(Duration.ofMillis(50L)).maxAttempt(5).retryableExceptions(
                            Arrays.asList(ConnectionException.class, SdkClientException.class, ServerException.class)).build();
    private final RetryUtils.RetryConfig finiteAttemptsRetryConfig =
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

    @ParameterizedTest
    @CsvSource({"012345678910.dkr.ecr.us-east-1.amazonaws,us-east-1", "012345678910.dkr.ecr.us-west-1.amazonaws,us-west-1"})
    void GIVEN_a_container_component_with_an_ecr_image_with_digest_WHEN_deployed_THEN_download_image_artifact(String url, String region)
            throws Exception {
        URI artifactUri =
                new URI("docker:" + url
                        + ".com/testimagepath/testimage@sha256:223057d6358a0530e4959c883e05199317cdc892f08667e6186133a0b5432948");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());

        when(ecrAccessor.getCredentials("012345678910", region))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().plusSeconds(60)));
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("testimagepath/testimage", image.getName());
        assertEquals("sha256:223057d6358a0530e4959c883e05199317cdc892f08667e6186133a0b5432948", image.getDigest());
        assertNull(image.getTag());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals(url + ".com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        verify(ecrAccessor).getCredentials("012345678910", region);
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_an_ecr_image_with_tag_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(ecrAccessor.getCredentials("012345678910", "us-east-1"))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().plusSeconds(60)));
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals("012345678910.dkr.ecr.us-east-1.amazonaws.com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        verify(ecrAccessor).getCredentials("012345678910", "us-east-1");
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_a_public_ecr_image_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri = new URI("docker:public.ecr.aws/a1b2c3d4/testimage:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("a1b2c3d4/testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("public.ecr.aws", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient).pullImage(image);
        verify(dockerClient, never()).login(any());
    }

    @Test
    void GIVEN_a_container_component_with_a_public_dockerhub_image_WHEN_deployed_THEN_download_image_artifact()
            throws Exception {
        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("library/alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_in_deployment_WHEN_docker_not_installed_THEN_fail_deployment() throws Exception {
        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(false);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString(DOCKER_NOT_INSTALLED_ERROR_MESSAGE));

        assertEquals("library/alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient, never()).pullImage(any());
    }

    @Test
    void GIVEN_a_container_component_with_image_in_dockerhub_WHEN_image_not_public_THEN_fail_deployment()
            throws Exception {
        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);
        doThrow(new InvalidImageOrAccessDeniedException(
                "Invalid image or login - repository does not exist or may require 'docker login'")).when(dockerClient)
                .pullImage(image);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to download docker image"));
        assertTrue(err.getCause() instanceof InvalidImageOrAccessDeniedException);

        assertEquals("library/alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_image_in_ecr_WHEN_when_failed_to_get_credentials_THEN_fail_deployment()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(ecrAccessor.getCredentials("012345678910", "us-east-1"))
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

        verify(ecrAccessor).getCredentials("012345678910", "us-east-1");
        verify(dockerClient, never()).login(any());
        verify(dockerClient, never()).pullImage(any());
    }

    @Test
    void GIVEN_a_container_component_WHENn_failed_to_pull_image_THEN_fail_deployment(ExtensionContext extensionContext)
            throws Exception {
        ignoreExceptionOfType(extensionContext, DockerServiceUnavailableException.class);

        URI artifactUri = new URI("docker:registry.hub.docker.com/library/alpine:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);
        // fail all retries
        doThrow(new DockerServiceUnavailableException("Service Unavailable")).when(dockerClient).pullImage(image);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to download docker image"));
        assertTrue(err.getCause() instanceof DockerServiceUnavailableException);

        assertEquals("library/alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
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
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(mqttClient.getMqttOnline()).thenReturn(new AtomicBoolean(false));
        // Fail on first 3 attempts, succeed on fourth attempt to simulate that pull would succeed when connectivity
        // comes back
        when(dockerClient.dockerInstalled()).thenReturn(true);
        doThrow(new DockerServiceUnavailableException("Service Unavailable"))
                .doThrow(new DockerServiceUnavailableException("Service Unavailable"))
                .doThrow(new DockerServiceUnavailableException("Service Unavailable")).doNothing().when(dockerClient)
                .pullImage(image);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("library/alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient, never()).login(any());
        // Invocations as many as the retry attempts should be expected
        verify(dockerClient, times(4)).pullImage(any());
        verify(mqttClient, times(3)).getMqttOnline();
    }

    @Test
    void GIVEN_connectivity_available_WHEN_failed_to_pull_image_intermittently_THEN_succeed_on_retries(
            ExtensionContext extensionContext) throws Exception {
        ignoreExceptionOfType(extensionContext, DockerServiceUnavailableException.class);

        URI artifactUri = new URI("docker:registry.hub.docker.com/alpine:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);
        // fail first attempt, succeed on next retry attempt, device is connected the whole time but there could be
        // temporary issues with docker cloud which can get resolved in a short span
        doThrow(new DockerServiceUnavailableException("Service Unavailable")).doNothing().when(dockerClient)
                .pullImage(image);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
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
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);
        // fail first attempt, succeed on next retry attempt, device is connected the whole time
        doThrow(new DockerServiceUnavailableException("Service Unavailable")).when(dockerClient).pullImage(image);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        Throwable err = assertThrows(PackageDownloadException.class, () -> downloader.download());
        assertThat(err.getMessage(), containsString("Failed to download docker image"));
        assertTrue(err.getCause() instanceof DockerServiceUnavailableException);

        assertEquals("library/alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient, never()).login(any());
        // Invocations as many as the retry count should be expected
        verify(dockerClient, times(2)).pullImage(any());
        verify(mqttClient, times(2)).getMqttOnline();
    }

    @Test
    void GIVEN_a_container_component_WHEN_greengrass_does_not_have_permissions_to_use_docker_daemon_THEN_fail_deployment()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);
        when(ecrAccessor.getCredentials("012345678910", "us-east-1"))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().plusSeconds(60)));
        doThrow(new UserNotAuthorizedForDockerException(
                "Got permission denied while trying to connect to the Docker daemon socket")).when(dockerClient)
                .login(any());

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

        verify(ecrAccessor).getCredentials("012345678910", "us-east-1");
        verify(dockerClient).login(any());
        verify(dockerClient, never()).pullImage(any());
    }

    @Test
    void GIVEN_a_container_component_with_no_registry_in_uri_WHEN_deployed_THEN_download_image_artifact_from_dockerhub()
            throws Exception {
        URI artifactUri = new URI("docker:alpine:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_no_digest_or_tag_in_uri_WHEN_deployed_THEN_assume_latest_image_version()
            throws Exception {
        URI artifactUri = new URI("docker:alpine");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        downloader.download();

        assertEquals("alpine", image.getName());
        assertEquals("latest", image.getTag());
        assertNull(image.getDigest());
        assertFalse(image.getRegistry().isEcrRegistry());
        assertFalse(image.getRegistry().isPrivateRegistry());
        assertEquals("registry.hub.docker.com/library", image.getRegistry().getEndpoint());

        verify(ecrAccessor, never()).getCredentials(anyString(), anyString());
        verify(dockerClient, never()).login(any());
        verify(dockerClient).pullImage(image);
    }

    @Test
    void GIVEN_a_container_component_with_private_ecr_image_WHEN_credentials_expire_THEN_refresh_credentials_and_retry()
            throws Exception {
        URI artifactUri = new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/testimage:sometag");
        Image image = Image.fromArtifactUri(ComponentArtifact.builder().artifactUri(artifactUri).build());
        // Use stale credentials in first login attempt to simulate credentials expiry due to device being offline
        // for longer than credential validity. For the second attempt, use the opposite to simulate login was
        // performed in time before credentials expired.
        when(ecrAccessor.getCredentials("012345678910", "us-east-1"))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().minusSeconds(300)))
                .thenReturn(new Registry.Credentials("username", "password", Instant.now().plusSeconds(300)));
        when(dockerClient.dockerInstalled()).thenReturn(true);

        DockerImageDownloader downloader = getDownloader(artifactUri);

        // Download should eventually succeed after refreshing credentials
        downloader.download();

        assertEquals("testimage", image.getName());
        assertEquals("sometag", image.getTag());
        assertNull(image.getDigest());
        assertTrue(image.getRegistry().isEcrRegistry());
        assertTrue(image.getRegistry().isPrivateRegistry());
        assertEquals("012345678910.dkr.ecr.us-east-1.amazonaws.com", image.getRegistry().getEndpoint());
        assertEquals("012345678910", image.getRegistry().getRegistryId());

        // Getting credentials should be performed twice because the first time, credentials expired
        verify(ecrAccessor, times(2)).getCredentials("012345678910", "us-east-1");
        verify(dockerClient).pullImage(image);
        verify(dockerClient).login(image.getRegistry());
    }

    @Test
    void GIVEN_a_artifact_with_private_ecr_image_THEN_check_if_image_used_by_others()
            throws Exception {
        URI artifactUri = new URI("450817829141.dkr.ecr.us-east-1.amazonaws.com/integrationdockerimage:latest");
        DockerImageDownloader downloader = spy(getDownloader(artifactUri));

        ComponentStore componentStore = mock(ComponentStore.class);
        Map<String, Set<String>> allVersions = new HashMap<String, Set<String>>();
        Set<String> versions = new HashSet<>();
        versions.add("1.0.0");
        allVersions.put("com.example.HelloWorld", versions);
        when(componentStore.listAvailableComponentVersions()).thenReturn(allVersions);
        ComponentRecipe recipe = new ComponentRecipe(RecipeFormatVersion.JAN_25_2020, "com.example.HelloWorld",
                new Semver("2.0.0", Semver.SemverType.NPM), "", "", null, new HashMap<String, Object>() {{
            put("LIFECYCLE_RUN_KEY", "java -jar {artifacts:path}/test.jar -x arg");
        }}, new ArrayList<ComponentArtifact>() {{ add(ComponentArtifact.builder().artifactUri(artifactUri).build());}}, Collections.emptyMap(), null);
        when(componentStore.getPackageRecipe(any())).thenReturn(recipe);

        assertTrue(downloader.ifImageUsedByOther(componentStore));
    }

    @Test
    void GIVEN_a_artifact_with_private_ecr_image_WHEN_image_not_used_by_others_THEN_remove_image()
            throws Exception {
        URI artifactUri = new URI("450817829141.dkr.ecr.us-east-1.amazonaws.com/integrationdockerimage:latest");
        DockerImageDownloader downloader = spy(getDownloader(artifactUri));

        doReturn(false).when(downloader).ifImageUsedByOther(any());
        doNothing().when(dockerClient).deleteImage(any());

        downloader.cleanup(any());

        verify(dockerClient, times(1)).deleteImage(any());
    }

    private DockerImageDownloader getDownloader(URI artifactUri) {
        DockerImageDownloader downloader = new DockerImageDownloader(TEST_COMPONENT_ID,
                ComponentArtifact.builder().artifactUri(artifactUri).build(), artifactDir, dockerClient, ecrAccessor,
                mqttClient);
        downloader.setInfiniteAttemptsRetryConfig(infiniteAttemptsRetryConfig);
        downloader.setFiniteAttemptsRetryConfig(finiteAttemptsRetryConfig);
        return downloader;
    }
}
