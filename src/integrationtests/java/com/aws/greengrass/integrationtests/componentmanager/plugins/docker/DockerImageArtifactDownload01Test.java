/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.integrationtests.componentmanager.plugins.docker;

import com.aws.greengrass.componentmanager.ComponentManager;
import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory;
import com.aws.greengrass.componentmanager.converter.RecipeLoader;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.plugins.docker.DefaultDockerClient;
import com.aws.greengrass.componentmanager.plugins.docker.EcrAccessor;
import com.aws.greengrass.componentmanager.plugins.docker.Registry;
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
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class DockerImageArtifactDownload01Test extends BaseITCase {

    private final PlatformResolver platformResolver = new PlatformResolver(null);
    private final RecipeLoader recipeLoader = new RecipeLoader(platformResolver);
    @Mock
    private DefaultDockerClient dockerClient;
    @Mock
    private MqttClient mqttClient;
    private ComponentManager componentManager;
    private Kernel kernel;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    private LazyCredentialProvider lazyCredentialProvider;
    @Mock
    private EcrClient eastEcrClient;
    @Mock
    private EcrClient westEcrClient;

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

        EcrAccessor ecrAccessor = new EcrAccessor(deviceConfiguration, lazyCredentialProvider);
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
        lenient().when(eastEcrClient.getAuthorizationToken(
                GetAuthorizationTokenRequest.builder().registryIds(Collections.singletonList("012345678910")).build())).thenReturn(responseEast);
        lenient().when(westEcrClient.getAuthorizationToken(
                GetAuthorizationTokenRequest.builder().registryIds(Collections.singletonList("78988")).build())).thenReturn(responseWest);
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
                new ComponentIdentifier("docker.image.from.ecr.private.registry01", new Semver("1.0.0"));
        componentManager.preparePackages(Collections.singletonList(componentId)).get();

        ArgumentCaptor<Registry> argument = ArgumentCaptor.forClass(Registry.class);
        verify(dockerClient, times(2)).login(argument.capture());
        List<Registry> list = argument.getAllValues();

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
