/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.GetComponentArtifactRequest;
import com.amazonaws.services.evergreen.model.GetComponentArtifactResult;
import com.aws.greengrass.componentmanager.ComponentTestResourceHelper;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class GreengrassRepositoryDownloaderTest {
    private static final String SHA256 = "SHA-256";

    @Mock
    private HttpURLConnection connection;

    @Mock
    private AWSEvergreen client;

    @Mock
    private GreengrassComponentServiceClientFactory clientFactory;

    private GreengrassRepositoryDownloader downloader;

    @Captor
    ArgumentCaptor<GetComponentArtifactRequest> getComponentArtifactRequestArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        when(clientFactory.getCmsClient()).thenReturn(client);
        this.downloader = Mockito.spy(new GreengrassRepositoryDownloader(clientFactory));
    }

    @Test
    void GIVEN_artifact_url_WHEN_attempt_download_THEN_task_succeed() throws Exception {
        GetComponentArtifactResult result =
                new GetComponentArtifactResult().withPreSignedUrl("https://www.amazon.com/artifact.txt");
        when(client.getComponentArtifact(getComponentArtifactRequestArgumentCaptor.capture())).thenReturn(result);

        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");
        when(connection.getInputStream()).thenReturn(Files.newInputStream(mockArtifactPath));

        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve("CoolService").resolve("1.0.0");
        Path artifactFilePath = saveToPath.resolve("artifactName");
        Files.createDirectories(saveToPath);
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance(SHA256).digest(Files.readAllBytes(mockArtifactPath)));

        downloader.downloadToPath(
                pkgId, ComponentArtifact.builder().artifactUri(new URI("greengrass:artifactName"))
                        .checksum(checksum).algorithm(SHA256).build(), saveToPath);

        GetComponentArtifactRequest generatedRequest = getComponentArtifactRequestArgumentCaptor.getValue();
        assertEquals("CoolService", generatedRequest.getComponentName());
        assertEquals("1.0.0", generatedRequest.getComponentVersion());
        assertNull(generatedRequest.getScope());
        assertEquals("artifactName", generatedRequest.getArtifactName());

        byte[] originalFile = Files.readAllBytes(mockArtifactPath);
        byte[] downloadFile = Files.readAllBytes(artifactFilePath);
        assertThat(Arrays.equals(originalFile, downloadFile), is(true));
        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_http_connection_error_WHEN_attempt_download_THEN_return_exception() throws Exception {
        GetComponentArtifactResult result =
                new GetComponentArtifactResult().withPreSignedUrl("https://www.amazon.com/artifact.txt");
        when(client.getComponentArtifact(any())).thenReturn(result);

        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenThrow(IOException.class);

        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        assertThrows(IOException.class, () -> downloader
                .downloadToPath(pkgId,
                        ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(),null));
    }

    @Test
    void GIVEN_filename_in_uri_WHEN_attempt_resolve_filename_THEN_parse_filename() {
        String filename = downloader.getFilename(ComponentArtifact.builder().artifactUri(
                URI.create("greengrass:abcd.jj")).build());
        assertThat(filename, is("abcd.jj"));
        filename = downloader.getFilename(ComponentArtifact.builder().artifactUri(
                URI.create("greengrass:abcd")).build());
        assertThat(filename, is("abcd"));
        filename = downloader.getFilename(ComponentArtifact.builder().artifactUri(
                URI.create("greengrass:jkdfjk/kdjfkdj/abcd.jj")).build());
        assertThat(filename, is("abcd.jj"));
    }
}
