/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager.plugins;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.DefaultDockerClient;
import com.aws.greengrass.componentmanager.plugins.EcrAccessor;
import com.aws.greengrass.componentmanager.plugins.Image;
import com.aws.greengrass.componentmanager.plugins.Registry;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.sun.org.apache.xerces.internal.impl.dv.util.Base64;
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
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DockerImageArtifactDownloadTest extends BaseITCase {

    private final PlatformResolver platformResolver = new PlatformResolver(null);
    private final RecipeLoader recipeLoader = new RecipeLoader(platformResolver);
    @Mock
    private EcrClient ecrClient;
    @Mock
    private DefaultDockerClient dockerClient;
    @Mock
    private MqttClient mqttClient;
    private ComponentManager componentManager;
    private Kernel kernel;

    @BeforeEach
    void before() throws Exception {
        Instant credentialsExpiry = Instant.now().plusSeconds(10);
        AuthorizationData authorizationData = AuthorizationData.builder()
                .authorizationToken(Base64.encode("username:password".getBytes(StandardCharsets.UTF_8)))
                .expiresAt(credentialsExpiry).build();
        GetAuthorizationTokenResponse response =
                GetAuthorizationTokenResponse.builder().authorizationData(authorizationData).build();
        lenient().when(ecrClient.getAuthorizationToken(any(GetAuthorizationTokenRequest.class))).thenReturn(response);

        lenient().when(dockerClient.dockerInstalled()).thenReturn(true);
        lenient().when(dockerClient.login(any(Registry.class))).thenReturn(true);
        lenient().when(dockerClient.pullImage(any(Image.class))).thenReturn(true);

        AtomicBoolean mqttOnline = new AtomicBoolean(true);
        lenient().when(mqttClient.getMqttOnline()).thenReturn(mqttOnline);

        kernel = new Kernel();

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);

        EcrAccessor ecrAccessor = new EcrAccessor(ecrClient);

        kernel.getContext().put(ComponentStore.class, store);
        kernel.getContext().put(EcrAccessor.class, ecrAccessor);
        kernel.getContext().put(DefaultDockerClient.class, dockerClient);
        kernel.getContext().put(MqttClient.class, mqttClient);

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
        verify(dockerClient, times(3)).dockerInstalled();
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
        verify(dockerClient, times(3)).dockerInstalled();
        verify(dockerClient, never()).login(any(Registry.class));
        verify(dockerClient, times(3)).pullImage(any(Image.class));
    }

    @Test
    void GIVEN_component_with_dockerhub_image_as_artifact_WHEN_prepare_packages_THEN_download_image() throws Exception {
        ComponentIdentifier componentId = new ComponentIdentifier("docker.image.from.dockerhub", new Semver("1.0.0"));
        componentManager.preparePackages(Collections.singletonList(componentId)).get();

        verify(ecrClient, never()).getAuthorizationToken(any(GetAuthorizationTokenRequest.class));
        verify(dockerClient, times(3)).dockerInstalled();
        verify(dockerClient, never()).login(any(Registry.class));
        verify(dockerClient, times(3)).pullImage(any(Image.class));
    }

    private void preloadLocalStoreContent() throws URISyntaxException, IOException {
        Path localStoreContentPath =
                Paths.get(DockerImageArtifactDownloadTest.class.getResource("dockerdownloader").toURI());
        System.out.println(localStoreContentPath.toString());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
    }
}
