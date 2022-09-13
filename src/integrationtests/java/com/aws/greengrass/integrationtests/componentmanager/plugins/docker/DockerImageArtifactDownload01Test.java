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
import com.aws.greengrass.componentmanager.plugins.docker.Image;
import com.aws.greengrass.componentmanager.plugins.docker.Registry;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.helper.PreloadComponentStoreHelper;
import com.aws.greengrass.integrationtests.BaseITCase;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
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
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
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
    private EcrAccessor ecrAccessor;


    @BeforeEach
    void before() throws Exception {
        lenient().when(dockerClient.dockerInstalled()).thenReturn(true);

        AtomicBoolean mqttOnline = new AtomicBoolean(true);
        lenient().when(mqttClient.getMqttOnline()).thenReturn(mqttOnline);

        kernel = new Kernel();

        NucleusPaths nucleusPaths = kernel.getNucleusPaths();
        nucleusPaths.setComponentStorePath(tempRootDir);
        ComponentStore store = new ComponentStore(nucleusPaths, platformResolver, recipeLoader);

        lenient().when(ecrAccessor.getCredentials("012345678910", "us-east-1")).thenReturn(new Registry.Credentials("eastUser", "eastPass", Instant.now().plusSeconds(60)));
        lenient().when(ecrAccessor.getCredentials("012345678910", "us-west-1")).thenReturn(new Registry.Credentials("westUser", "westPass", Instant.now().plusSeconds(60)));

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

        /* we try to retrieve the username and password in the docker login
        and verify that it is equal to the username and password for stubbed username and password
         */

        verify(dockerClient, times(6)).dockerInstalled();
        verify(dockerClient, times(6)).login(any(Registry.class));
        verify(dockerClient, times(6)).pullImage(any(Image.class));
    }


    private void preloadLocalStoreContent() throws URISyntaxException, IOException {
        Path localStoreContentPath =
                Paths.get(DockerImageArtifactDownload01Test.class.getResource("downloader").toURI());
        PreloadComponentStoreHelper.preloadRecipesFromTestResourceDir(localStoreContentPath.resolve("recipes"),
                kernel.getNucleusPaths().recipePath());
    }
}
