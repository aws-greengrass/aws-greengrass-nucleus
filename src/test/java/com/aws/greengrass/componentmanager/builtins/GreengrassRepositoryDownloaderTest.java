/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.ComponentTestResourceHelper;
import com.aws.greengrass.componentmanager.GreengrassComponentServiceClientFactory;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.RetryUtils;
import com.vdurmont.semver4j.Semver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.greengrassv2.GreengrassV2Client;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentVersionArtifactRequest;
import software.amazon.awssdk.services.greengrassv2.model.GetComponentVersionArtifactResponse;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.core.Is.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    private static final String TEST_ARN = "arn";
    @Captor
    ArgumentCaptor<GetComponentVersionArtifactRequest> getComponentVersionArtifactRequestArgumentCaptor;
    @Mock
    private HttpURLConnection connection;
    @Mock
    private GreengrassV2Client client;
    @Mock
    private GreengrassComponentServiceClientFactory clientFactory;
    @Mock
    private ComponentStore componentStore;

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
        ComponentArtifact artifact = ComponentArtifact.builder().algorithm(SHA256).checksum(checksum)
                .artifactUri(new URI("greengrass:774pP05xtua0RCcwj9uALSdAqGr_vC631EdOBkJxnec=/artifact.txt")).build();
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));

        lenient().when(componentStore.getRecipeMetadata(pkgId)).thenReturn(new RecipeMetadata(TEST_ARN));

        Path testCache = ComponentTestResourceHelper.getPathForLocalTestCache();
        Path saveToPath = testCache.resolve("CoolService").resolve("1.0.0");
        Files.createDirectories(saveToPath);

        GreengrassRepositoryDownloader downloader =
                spy(new GreengrassRepositoryDownloader(clientFactory, pkgId, artifact, saveToPath, componentStore));

        assertThat(downloader.getArtifactFilename(), is("artifact.txt"));

        // mock requests to get downloadSize and local file name
        GetComponentVersionArtifactResponse result =
                GetComponentVersionArtifactResponse.builder()
                        .preSignedUrl("https://www.amazon.com/artifact.txt").build();
        when(client.getComponentVersionArtifact(getComponentVersionArtifactRequestArgumentCaptor.capture()))
                .thenReturn(result);

        // mock requests to return partial stream
        doReturn(connection).when(downloader).connect(any());
        when(connection.getContentLengthLong()).thenReturn(Files.size(mockArtifactPath));
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_OK)
                .thenReturn(HttpURLConnection.HTTP_PARTIAL);
        when(connection.getInputStream()).thenReturn(Files.newInputStream(mockArtifactPath));

        downloader.download();

        GetComponentVersionArtifactRequest generatedRequest =
                getComponentVersionArtifactRequestArgumentCaptor.getValue();
        assertEquals(TEST_ARN, generatedRequest.arn());
        assertEquals("774pP05xtua0RCcwj9uALSdAqGr_vC631EdOBkJxnec=/artifact.txt", generatedRequest.artifactName());

        byte[] originalFile = Files.readAllBytes(mockArtifactPath);
        Path artifactFilePath = saveToPath.resolve("artifact.txt");
        byte[] downloadFile = Files.readAllBytes(artifactFilePath);
        assertThat(Arrays.equals(originalFile, downloadFile), is(true));
        ComponentTestResourceHelper.cleanDirectory(testCache);
    }

    @Test
    void GIVEN_http_connection_error_WHEN_attempt_download_THEN_retry_called(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, IOException.class);

        GetComponentVersionArtifactResponse result =
                GetComponentVersionArtifactResponse.builder().preSignedUrl("https://www.amazon.com/artifact.txt")
                        .build();
        when(client.getComponentVersionArtifact(any(GetComponentVersionArtifactRequest.class))).thenReturn(result);
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        lenient().when(componentStore.getRecipeMetadata(pkgId)).thenReturn(new RecipeMetadata(TEST_ARN));
        GreengrassRepositoryDownloader downloader = spy(new GreengrassRepositoryDownloader(clientFactory, pkgId,
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(), null, componentStore));
        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenThrow(IOException.class);

        downloader.setClientExceptionRetryConfig(RetryUtils.RetryConfig.builder().maxAttempt(2)
                .retryableExceptions(Arrays.asList(SdkClientException.class, IOException.class)).build());

        PackageDownloadException e = assertThrows(PackageDownloadException.class,
                () -> downloader.download(0, 100, MessageDigest.getInstance("SHA-256")));

        // assert retry called
        verify(connection, times(2)).getResponseCode();
        verify(connection, times(2)).disconnect();
        assertThat(e.getLocalizedMessage(), containsStringIgnoringCase("Failed to download artifact"));
    }

    @Test
    void GIVEN_http_connection_bad_request_WHEN_attempt_download_THEN_download_error_thrown() throws Exception {
        GetComponentVersionArtifactResponse result =
                GetComponentVersionArtifactResponse.builder()
                        .preSignedUrl("https://www.amazon.com/artifact.txt").build();
        when(client.getComponentVersionArtifact(any(GetComponentVersionArtifactRequest.class))).thenReturn(result);
        ComponentIdentifier pkgId = new ComponentIdentifier("CoolService", new Semver("1.0.0"));
        lenient().when(componentStore.getRecipeMetadata(pkgId)).thenReturn(new RecipeMetadata(TEST_ARN));
        GreengrassRepositoryDownloader downloader = spy(new GreengrassRepositoryDownloader(clientFactory, pkgId,
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(), null, componentStore));
        doReturn(connection).when(downloader).connect(any());
        when(connection.getResponseCode()).thenReturn(HttpURLConnection.HTTP_BAD_REQUEST);

        PackageDownloadException e = assertThrows(PackageDownloadException.class,
                () -> downloader.download(0, 100, MessageDigest.getInstance("SHA-256")));

        // assert retry called
        verify(connection, times(1)).getResponseCode();
        verify(connection, times(1)).disconnect();
        assertThat(e.getLocalizedMessage(), containsStringIgnoringCase("Failed to download the artifact"));
    }

    @Test
    void GIVEN_filename_in_uri_WHEN_attempt_resolve_filename_THEN_parse_filename() {
        String filename = GreengrassRepositoryDownloader
                .getArtifactFilename(ComponentArtifact.builder().artifactUri(URI.create("greengrass:abcd.jj")).build());
        assertThat(filename, is("abcd.jj"));
        filename = GreengrassRepositoryDownloader
                .getArtifactFilename(ComponentArtifact.builder().artifactUri(URI.create("greengrass:abcd")).build());
        assertThat(filename, is("abcd"));
        filename = GreengrassRepositoryDownloader.getArtifactFilename(
                ComponentArtifact.builder().artifactUri(URI.create("greengrass:jkdfjk/kdjfkdj/abcd.jj")).build());
        assertThat(filename, is("abcd.jj"));
    }
}
