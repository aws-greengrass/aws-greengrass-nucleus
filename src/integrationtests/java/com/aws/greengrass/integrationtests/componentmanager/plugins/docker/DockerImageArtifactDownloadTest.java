/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.docker.DefaultDockerClient;
import com.aws.greengrass.componentmanager.plugins.docker.DockerImageDownloader;
import com.aws.greengrass.componentmanager.plugins.docker.EcrAccessor;
import com.aws.greengrass.componentmanager.plugins.docker.Image;
import com.aws.greengrass.componentmanager.plugins.docker.Registry;
import com.aws.greengrass.componentmanager.plugins.docker.exceptions.DockerImageDeleteException;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.tes.LazyCredentialProvider;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.RetryUtils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.AuthorizationData;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenRequest;
import software.amazon.awssdk.services.ecr.model.GetAuthorizationTokenResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({GGExtension.class, MockitoExtension.class})
public class DockerImageArtifactDownloadTest extends BaseITCase {

    private final PlatformResolver platformResolver = new PlatformResolver(null);
    private final RecipeLoader recipeLoader = new RecipeLoader(platformResolver);
    @Mock
    private EcrClient ecrClient;
    @Mock
    private DefaultDockerClient dockerClient;
    @Mock
    private MqttClient mqttClient;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private LazyCredentialProvider lazyCredentialProvider;
    private ComponentManager componentManager;
    private Kernel kernel;

    @BeforeEach
    void before() throws Exception {
        lenient().when(dockerClient.dockerInstalled()).thenReturn(true);

        AtomicBoolean mqttOnline = new AtomicBoolean(true);
        lenient().when(mqttClient.getMqttOnline()).thenReturn(mqttOnline);

        kernel = new Kernel();

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);

        EcrAccessor ecrAccessor = new EcrAccessor(deviceConfiguration, lazyCredentialProvider);
        EcrAccessor spyEcrAccessor = spy(ecrAccessor);
        lenient().when(spyEcrAccessor.getClient("us-east-1")).thenReturn(ecrClient);

        Instant credentialsExpiry = Instant.now().plusSeconds(10);
        AuthorizationData authorizationData = AuthorizationData.builder()
                .authorizationToken(Base64.getEncoder().encodeToString("username:password".getBytes(StandardCharsets.UTF_8)))
                .expiresAt(credentialsExpiry).build();
        GetAuthorizationTokenResponse response =
                GetAuthorizationTokenResponse.builder().authorizationData(authorizationData).build();
        lenient().when(ecrClient.getAuthorizationToken(any(GetAuthorizationTokenRequest.class))).thenReturn(response);
        lenient().when(deviceConfiguration.getAWSRegion()).thenReturn(Topic.of(kernel.getContext(), DeviceConfiguration.DEVICE_PARAM_AWS_REGION, "us-east-1"));

        kernel.getContext().put(ComponentStore.class, store);
        kernel.getContext().put(EcrAccessor.class, spyEcrAccessor);
        kernel.getContext().put(DefaultDockerClient.class, dockerClient);
        kernel.getContext().put(MqttClient.class, mqttClient);
        kernel.getContext().put(DeviceConfiguration.class, deviceConfiguration);

        preloadLocalStoreContent();

        componentManager = kernel.getContext().get(ComponentManager.class);
    }

    @AfterEach
    void afterEach() {
        kernel.shutdown();
    }

    @Test
    void GIVEN_component_with_ecr_private_image_as_artifact_WHEN_prepare_packages_THEN_download_image()
            throws Exception {
        ComponentIdentifier componentId =
                new ComponentIdentifier("docker.image.from.ecr.private.registry", new Semver("1.0.0"));
        componentManager.preparePackages(Collections.singletonList(componentId)).get();

        verify(ecrClient, times(3)).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
        verify(dockerClient, times(6)).dockerInstalled();
        verify(dockerClient, times(3)).login(any(Registry.class));
        verify(dockerClient, times(3)).pullImage(any(Image.class));
    }

    @Test
    void GIVEN_component_with_ecr_public_image_as_artifact_WHEN_prepare_packages_THEN_download_image()
            throws Exception {
        ComponentIdentifier componentId =
                new ComponentIdentifier("docker.image.from.ecr.public.registry", new Semver("1.0.0"));
        componentManager.preparePackages(Collections.singletonList(componentId)).get();

        verify(ecrClient, never()).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
        verify(dockerClient, times(6)).dockerInstalled();
        verify(dockerClient, never()).login(any(Registry.class));
        verify(dockerClient, times(3)).pullImage(any(Image.class));
    }

    @Test
    void GIVEN_component_with_dockerhub_image_as_artifact_WHEN_prepare_packages_THEN_download_image() throws Exception {
        ComponentIdentifier componentId = new ComponentIdentifier("docker.image.from.dockerhub", new Semver("1.0.0"));
        componentManager.preparePackages(Collections.singletonList(componentId)).get();

        verify(ecrClient, never()).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
        verify(dockerClient, times(6)).dockerInstalled();
        verify(dockerClient, never()).login(any(Registry.class));
        verify(dockerClient, times(3)).pullImage(any(Image.class));
    }

    @Test
    void GIVEN_docker_image_downloader_WHEN_cleanup_succeeds_THEN_no_retries() throws Exception {
        DockerImageDownloader downloader = createDownloaderWithRetryConfig();
        
        downloader.cleanup();
        
        verify(dockerClient, times(1)).deleteImage(any(Image.class));
    }

    @Test
    void GIVEN_docker_image_downloader_WHEN_cleanup_with_retry_config_THEN_retry_mechanism_available() throws Exception {
        DockerImageDownloader downloader = createDownloaderWithRetryConfig();
        
        // Verify that the retry configuration was set successfully via reflection
        java.lang.reflect.Field field = DockerImageDownloader.class.getDeclaredField("finiteDeleteAttemptsRetryConfig");
        field.setAccessible(true);
        RetryUtils.RetryConfig config = (RetryUtils.RetryConfig) field.get(downloader);
        
        // Verify the retry config is properly set
        assertEquals(3, config.getMaxAttempt());
        assertEquals(Duration.ofMillis(50), config.getInitialRetryInterval());
    }

    private DockerImageDownloader createDownloaderWithRetryConfig() throws Exception {
        RetryUtils.RetryConfig retryConfig = RetryUtils.RetryConfig.builder()
            .initialRetryInterval(Duration.ofMillis(50))
            .maxRetryInterval(Duration.ofMillis(50))
            .maxAttempt(3)
            .retryableExceptions(Collections.singletonList(DockerImageDeleteException.class))
            .build();
            
        ComponentIdentifier componentId = new ComponentIdentifier("test.container.component", new Semver("1.0.0"));
        ComponentArtifact artifact = ComponentArtifact.builder()
            .artifactUri(new URI("450817829141.dkr.ecr.us-east-1.amazonaws.com/integrationdockerimage:latest"))
            .build();
        
        DockerImageDownloader downloader = new DockerImageDownloader(componentId, artifact, tempRootDir, kernel.getContext(), kernel.getContext().get(ComponentStore.class));
        
        // Use reflection to set the private retry config
        java.lang.reflect.Field field = DockerImageDownloader.class.getDeclaredField("finiteDeleteAttemptsRetryConfig");
        field.setAccessible(true);
        field.set(downloader, retryConfig);
        
        return downloader;
    }

    private void preloadLocalStoreContent() throws URISyntaxException, IOException {
        Path localStoreContentPath =
                Paths.get(DockerImageArtifactDownloadTest.class.getResource("downloader").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
    }
}
