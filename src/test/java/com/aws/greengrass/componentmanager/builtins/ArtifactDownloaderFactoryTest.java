/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.exceptions.MissingRequiredComponentsException;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.S3SdkClientFactory;
import com.vdurmont.semver4j.Semver;
import org.hamcrest.core.IsInstanceOf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory.DOCKER_PLUGIN_REQUIRED_ERROR_MSG;
import static com.aws.greengrass.componentmanager.builtins.ArtifactDownloaderFactory.TOKEN_EXCHANGE_SERVICE_REQUIRED_ERROR_MSG;
import static com.aws.greengrass.componentmanager.plugins.docker.DockerApplicationManagerService.DOCKER_MANAGER_PLUGIN_SERVICE_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class ArtifactDownloaderFactoryTest {

    Path testDir = Paths.get("foo");

    @Mock
    S3SdkClientFactory s3SdkClientFactory;

    @Mock
    GreengrassServiceClientFactory greengrassServiceClientFactory;

    @Mock
    ComponentStore componentStore;

    @Mock
    Context context;

    ArtifactDownloaderFactory artifactDownloaderFactory;

    @BeforeEach
    public void setup() {
        artifactDownloaderFactory =
                new ArtifactDownloaderFactory(s3SdkClientFactory, greengrassServiceClientFactory,
                        componentStore, context);
    }

    @Test
    void GIVEN_s3_artifact_THEN_return_s3_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeServiceWithArtifactsInS3", new Semver("1.0.0"));

        ComponentArtifact artifact =
                ComponentArtifact.builder().artifactUri(new URI("s3://bucket/path/to/key")).build();

        ArtifactDownloader artifactDownloader =
                artifactDownloaderFactory.getArtifactDownloader(pkgId, artifact, testDir);
        assertThat(artifactDownloader, IsInstanceOf.instanceOf(S3Downloader.class));
    }


    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_attempt_download_artifact_THEN_invoke_gg_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        ComponentArtifact artifact = ComponentArtifact.builder().artifactUri(new URI("greengrass:binary2")).build();

        ArtifactDownloader artifactDownloader =
                artifactDownloaderFactory.getArtifactDownloader(pkgId, artifact, testDir);
        assertThat(artifactDownloader, IsInstanceOf.instanceOf(GreengrassRepositoryDownloader.class));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0" + ".0"));
        ComponentArtifact artifact = ComponentArtifact.builder().artifactUri(new URI("binary1")).build();
        Exception exception = assertThrows(PackageLoadingException.class,
                () -> artifactDownloaderFactory.getArtifactDownloader(pkgId, artifact, testDir));
        assertThat(exception.getMessage(), is("artifact URI scheme null is not supported yet"));
    }


    @Test
    void GIVEN_artifact_provider_not_supported_WHEN_attempt_download_THEN_throw_package_exception() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        ComponentArtifact artifact = ComponentArtifact.builder().artifactUri(new URI("foo:bar")).build();
        Exception exception = assertThrows(PackageLoadingException.class,
                () -> artifactDownloaderFactory.getArtifactDownloader(pkgId, artifact, testDir));
        assertThat(exception.getMessage(), is("artifact URI scheme FOO is not supported yet"));
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_download_prereq_component_THEN_succeed_1()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Private ECR image
        List<ComponentArtifact> artifacts = Collections.singletonList(ComponentArtifact.builder()
                .artifactUri(new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/test_image")).build());

        List<ComponentIdentifier> dependencyClosure =
                Arrays.asList(new ComponentIdentifier(DOCKER_MANAGER_PLUGIN_SERVICE_NAME, new Semver("2.0.0")),
                        new ComponentIdentifier("aws.greengrass.TokenExchangeService", new Semver("2.0.0")),
                        testComponent);
        artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent, dependencyClosure);
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_download_prereq_component_THEN_succeed_2()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Public ECR image
        List<ComponentArtifact> artifacts = Collections.singletonList(
                ComponentArtifact.builder().artifactUri(new URI("docker:public.ecr.aws/a1b2c3d4/testimage:sometag"))
                        .build());

        List<ComponentIdentifier> dependencyClosure =
                Arrays.asList(new ComponentIdentifier(DOCKER_MANAGER_PLUGIN_SERVICE_NAME, new Semver("2.0.0")),
                        testComponent);
        artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent, dependencyClosure);
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_download_prereq_component_THEN_succeed_3()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Any dockerhub image
        List<ComponentArtifact> artifacts = Collections.singletonList(ComponentArtifact.builder()
                .artifactUri(new URI("docker:registry.hub.docker.com/library/alpine:sometag")).build());

        List<ComponentIdentifier> dependencyClosure =
                Arrays.asList(new ComponentIdentifier(DOCKER_MANAGER_PLUGIN_SERVICE_NAME, new Semver("2.0.0")),
                        testComponent);
        artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent, dependencyClosure);
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_no_download_prereq_component_THEN_fail_1()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Private ECR image
        List<ComponentArtifact> artifacts = Collections.singletonList(ComponentArtifact.builder()
                .artifactUri(new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/test_image")).build());

        List<ComponentIdentifier> dependencyClosure = Arrays.asList(testComponent);
        Throwable err = assertThrows(MissingRequiredComponentsException.class,
                () -> artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent,
                        dependencyClosure));
        assertThat(err.getMessage(), containsString(DOCKER_PLUGIN_REQUIRED_ERROR_MSG));
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_no_download_prereq_component_THEN_fail_2()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Private ECR image
        List<ComponentArtifact> artifacts = Collections.singletonList(ComponentArtifact.builder()
                .artifactUri(new URI("docker:012345678910.dkr.ecr.us-east-1.amazonaws.com/test_image")).build());

        List<ComponentIdentifier> dependencyClosure =
                Arrays.asList(new ComponentIdentifier(DOCKER_MANAGER_PLUGIN_SERVICE_NAME, new Semver("2.0.0")),
                        testComponent);
        Throwable err = assertThrows(MissingRequiredComponentsException.class,
                () -> artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent,
                        dependencyClosure));
        assertThat(err.getMessage(), containsString(TOKEN_EXCHANGE_SERVICE_REQUIRED_ERROR_MSG));
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_no_download_prereq_component_THEN_fail_3()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Public ECR image
        List<ComponentArtifact> artifacts = Collections.singletonList(
                ComponentArtifact.builder().artifactUri(new URI("docker:public.ecr.aws/a1b2c3d4/testimage:sometag"))
                        .build());

        List<ComponentIdentifier> dependencyClosure = Arrays.asList(testComponent);
        Throwable err = assertThrows(MissingRequiredComponentsException.class,
                () -> artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent,
                        dependencyClosure));
        assertThat(err.getMessage(), containsString(DOCKER_PLUGIN_REQUIRED_ERROR_MSG));
    }

    @Test
    void GIVEN_deployment_has_component_requiring_download_plugins_WHEN_deployment_has_no_download_prereq_component_THEN_fail_4()
            throws Exception {
        ComponentIdentifier testComponent = new ComponentIdentifier("test.component", new Semver("1.0.0"));
        // Any dockerhub image
        List<ComponentArtifact> artifacts = Collections.singletonList(ComponentArtifact.builder()
                .artifactUri(new URI("docker:registry.hub.docker.com/library/alpine:sometag")).build());

        List<ComponentIdentifier> dependencyClosure = Arrays.asList(testComponent);
        Throwable err = assertThrows(MissingRequiredComponentsException.class,
                () -> artifactDownloaderFactory.checkDownloadPrerequisites(artifacts, testComponent,
                        dependencyClosure));
        assertThat(err.getMessage(), containsString(DOCKER_PLUGIN_REQUIRED_ERROR_MSG));
    }
}
