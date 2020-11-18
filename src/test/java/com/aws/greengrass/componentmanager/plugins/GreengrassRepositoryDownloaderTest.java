/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.plugins;

import com.amazonaws.services.evergreen.AWSEvergreen;
import com.amazonaws.services.evergreen.model.GetComponentVersionArtifactDeprecatedRequest;
import com.amazonaws.services.evergreen.model.GetComponentVersionArtifactDeprecatedResult;
import com.aws.greengrass.componentmanager.ComponentTestResourceHelper;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
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
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Captor
    ArgumentCaptor<GetComponentVersionArtifactDeprecatedRequest> getComponentArtifactRequestArgumentCaptor;

    @BeforeEach
    void beforeEach() {
        lenient().when(clientFactory.getCmsClient()).thenReturn(client);
    }

    @Test
    void GIVEN_artifact_url_WHEN_attempt_download_THEN_task_succeed() throws Exception {
        // build downloader
        Path mockArtifactPath = ComponentTestResourceHelper
                .getPathForTestPackage(ComponentTestResourceHelper.MONITORING_SERVICE_PACKAGE_NAME, "1.0.0")
                .resolve("monitor_artifact_100.txt");
        String checksum = Base64.getEncoder()
                .encodeToString(MessageDigest.getInstance(SHA256).digest(Files.readAllBytes(mockArtifactPath)));
        ComponentArtifact artifact = ComponentArtifact.builder()
                .algorithm(SHA256)
                .checksum(checksum)
                .artifactUri(new URI("greengrass:artifactName"))
                .build();
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve("CoolService").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        GreengrassRepositoryDownloader downloader = spy(new GreengrassRepositoryDownloader(clientFactory,
                pkgId, artifact, saveToPath));

        // mock requests to get downloadSize and local file name
        GetComponentVersionArtifactDeprecatedResult result =
                new GetComponentVersionArtifactDeprecatedResult().withPreSignedUrl("https://www.amazon.com/artifact.txt");
        when(client.getComponentVersionArtifactDeprecated(getComponentArtifactRequestArgumentCaptor.capture())).thenReturn(result);

        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK);
        when(connection.getContentLengthLong()).thenReturn(Files.size(mockArtifactPath));
        when(connection.getHeaderField("Content-Disposition")).thenReturn("filename=artifact.txt");
        assertThat(downloader.getArtifactFilename(), is("artifact.txt"));

        // mock requests to return partial stream
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_PARTIAL);
        when(connection.getInputStream()).thenReturn(Files.newInputStream(mockArtifactPath));

        Path artifactFilePath = saveToPath.resolve("artifact.txt");

        downloader.downloadToPath();

        GetComponentVersionArtifactDeprecatedRequest generatedRequest = getComponentArtifactRequestArgumentCaptor.getValue();
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
    void GIVEN_http_connection_error_WHEN_attempt_download_THEN_retry_called() throws Exception {
        GreengrassRepositoryDownloader.MAX_RETRY = 2;
        GetComponentVersionArtifactDeprecatedResult result =
                new GetComponentVersionArtifactDeprecatedResult().withPreSignedUrl("https://www.amazon.com/artifact.txt");
        when(client.getComponentVersionArtifactDeprecated(any())).thenReturn(result);
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        GreengrassRepositoryDownloader downloader = spy(new GreengrassRepositoryDownloader(clientFactory,
                pkgId, ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(), null));
        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenThrow(IOException.class);

        PackageDownloadException e = assertThrows(PackageDownloadException.class,
                () -> downloader.download(0, 100, MessageDigest.getInstance("SHA-256")));

        // assert retry called
        verify(connection, times(2)).getResponseCode();
        verify(connection, times(2)).disconnect();
        assertThat(e.getLocalizedMessage(),
                containsStringIgnoringCase("Fail to execute establish HTTP connection after retrying 2 times"));
    }

    @Test
    void GIVEN_http_connection_bad_request_WHEN_attempt_download_THEN_download_error_thrown() throws Exception {
        GetComponentVersionArtifactDeprecatedResult result =
                new GetComponentVersionArtifactDeprecatedResult().withPreSignedUrl("https://www.amazon.com/artifact.txt");
        when(client.getComponentVersionArtifactDeprecated(any())).thenReturn(result);
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        GreengrassRepositoryDownloader downloader = spy(new GreengrassRepositoryDownloader(clientFactory,
                pkgId, ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(), null));
        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_BAD_REQUEST);

        PackageDownloadException e = assertThrows(PackageDownloadException.class,
                () -> downloader.download(0, 100, MessageDigest.getInstance("SHA-256")));

        // assert retry called
        verify(connection, times(1)).getResponseCode();
        verify(connection, times(1)).disconnect();
        assertThat(e.getLocalizedMessage(),
                containsStringIgnoringCase("HTTP Error: " + HttpURLConnection.HTTP_BAD_REQUEST));
    }

    @Test
    void GIVEN_filename_in_disposition_WHEN_attempt_resolve_filename_THEN_parse_filename() throws Exception {
        String filename = GreengrassRepositoryDownloader
                .extractFilename(new URL("https://www.amazon.com/artifact.txt"),
                "attachment; " + "filename=\"filename.jpg\"");

        assertThat(filename, is("filename.jpg"));
    }

    @Test
    void GIVEN_filename_in_url_WHEN_attempt_resolve_filename_THEN_parse_filename() throws Exception {
        String filename = GreengrassRepositoryDownloader
                .extractFilename(new URL("https://www.amazon.com/artifact.txt?key=value"), "attachment");

        assertThat(filename, is("artifact.txt"));
    }

}
