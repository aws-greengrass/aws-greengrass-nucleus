/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageLoadingException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith({MockitoExtension.class, GGExtension.class})
public class ArtifactDownloaderFactoryTest {

    Path testDir = Paths.get("foo");

    @Mock
    S3SdkClientFactory s3SdkClientFactory;

    @Mock
    GreengrassComponentServiceClientFactory greengrassComponentServiceClientFactory;

    @Mock
    ComponentStore componentStore;

    ArtifactDownloaderFactory artifactDownloaderFactory;

    @BeforeEach
    public void setup() {
        artifactDownloaderFactory = new ArtifactDownloaderFactory(
                s3SdkClientFactory, greengrassComponentServiceClientFactory, componentStore);
    }

    @Test
    void GIVEN_s3_artifact_THEN_return_s3_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("SomeServiceWithArtifactsInS3", new Semver("1.0.0"));

        ComponentArtifact artifact = ComponentArtifact.builder()
                .artifactUri(new URI("s3://bucket/path/to/key"))
                .build();

        ArtifactDownloader artifactDownloader = artifactDownloaderFactory
                .getArtifactDownloader(pkgId, artifact, testDir);
        assertThat(artifactDownloader, IsInstanceOf.instanceOf(S3Downloader.class));
    }


    @Test
    void GIVEN_artifact_from_gg_repo_WHEN_attempt_download_artifact_THEN_invoke_gg_downloader() throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        ComponentArtifact artifact = ComponentArtifact.builder()
                .artifactUri(new URI("greengrass:binary2"))
                .build();

        ArtifactDownloader artifactDownloader = artifactDownloaderFactory
                .getArtifactDownloader(pkgId, artifact, testDir);
        assertThat(artifactDownloader, IsInstanceOf.instanceOf(GreengrassRepositoryDownloader.class));
    }

    @Test
    void GIVEN_artifact_url_no_scheme_WHEN_attempt_download_THEN_throw_package_exception()
            throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0" + ".0"));
        ComponentArtifact artifact = ComponentArtifact.builder().artifactUri(new URI("binary1")).build();
        Exception exception = assertThrows(PackageLoadingException.class,
                () -> artifactDownloaderFactory.getArtifactDownloader(pkgId, artifact, testDir));
        assertThat(exception.getMessage(), is("artifact URI scheme null is not supported yet"));
    }


    @Test
    void GIVEN_artifact_provider_not_supported_WHEN_attempt_download_THEN_throw_package_exception()
            throws Exception {
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        ComponentArtifact artifact = ComponentArtifact.builder().artifactUri(new URI("docker:image1")).build();
        Exception exception = assertThrows(PackageLoadingException.class,
                () -> artifactDownloaderFactory.getArtifactDownloader(pkgId, artifact, testDir));
        assertThat(exception.getMessage(), is("artifact URI scheme DOCKER is not supported yet"));
    }
}
