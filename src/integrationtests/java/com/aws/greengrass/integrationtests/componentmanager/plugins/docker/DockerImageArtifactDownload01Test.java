/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.docker.DefaultDockerClient;
import com.aws.greengrass.componentmanager.plugins.docker.EcrAccessor;
import com.aws.greengrass.componentmanager.plugins.docker.Registry;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.tes.LazyCredentialProvider;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.ProxyUtils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.regions.Region;
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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DockerImageArtifactDownload01Test extends BaseITCase {

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

    @Mock
    private LazyCredentialProvider lazyCredentialProvider;

    @BeforeEach
    @SuppressWarnings("PMD.CloseResource")
    void before() throws Exception {
        lenient().when(dockerClient.dockerInstalled()).thenReturn(true);

        AtomicBoolean mqttOnline = new AtomicBoolean(true);
        lenient().when(mqttClient.getMqttOnline()).thenReturn(mqttOnline);

        kernel = new Kernel();

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);

        EcrClient eastEcrClient = EcrClient.builder().httpClient(ProxyUtils.getSdkHttpClient())
                .region(Region.of("us-east-1"))
                .credentialsProvider(lazyCredentialProvider).build();
        EcrClient westEcrClient = EcrClient.builder().httpClient(ProxyUtils.getSdkHttpClient())
                .region(Region.of("us-west-1"))
                .credentialsProvider(lazyCredentialProvider).build();

        EcrAccessor ecrAccessor = new EcrAccessor(ecrClient);
        EcrAccessor spyEcrAccessor = spy(ecrAccessor);
        lenient().when(spyEcrAccessor.getClient("us-east-1")).thenReturn(eastEcrClient);
        lenient().when(spyEcrAccessor.getClient("us-west-1")).thenReturn(westEcrClient);


        Instant credentialsExpiry = Instant.now().plusSeconds(10);
        AuthorizationData authorizationDataEast = AuthorizationData.builder()
                .authorizationToken(Base64.getEncoder().encodeToString("eastUser:eastPass".getBytes(StandardCharsets.UTF_8)))
                .expiresAt(credentialsExpiry).build();
        AuthorizationData authorizationDataWest = AuthorizationData.builder()
                .authorizationToken(Base64.getEncoder().encodeToString("westUser:westPass".getBytes(StandardCharsets.UTF_8)))
                .expiresAt(credentialsExpiry).build();

        GetAuthorizationTokenResponse responseEast =
                GetAuthorizationTokenResponse.builder().authorizationData(authorizationDataEast).build();
        GetAuthorizationTokenResponse responseWest =
                GetAuthorizationTokenResponse.builder().authorizationData(authorizationDataWest).build();
        lenient().when(ecrClient.getAuthorizationToken(
                GetAuthorizationTokenRequest.builder().registryIds(Collections.singletonList("012345678910")).build())).thenReturn(responseEast);
        lenient().when(ecrClient.getAuthorizationToken(
                GetAuthorizationTokenRequest.builder().registryIds(Collections.singletonList("78988")).build())).thenReturn(responseWest);

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
                new ComponentIdentifier("docker.image.from.ecr.private.registry01", new Semver("1.0.0"));
        componentManager.preparePackages(Collections.singletonList(componentId)).get();

        ArgumentCaptor argument = ArgumentCaptor.forClass(Registry.class);
        verify(dockerClient, times(2)).login((Registry) argument.capture());
        List<Registry> list = (List<Registry>) argument.getAllValues();

        Assertions.assertEquals("eastUser", list.get(0).getCredentials().getUsername());
        Assertions.assertEquals("eastPass", list.get(0).getCredentials().getPassword());
        Assertions.assertEquals("westUser", list.get(1).getCredentials().getUsername());
        Assertions.assertEquals("westPass", list.get(1).getCredentials().getPassword());
    }


    private void preloadLocalStoreContent() throws URISyntaxException, IOException {
        Path localStoreContentPath =
                Paths.get(DockerImageArtifactDownload01Test.class.getResource("downloader").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
    }
}
