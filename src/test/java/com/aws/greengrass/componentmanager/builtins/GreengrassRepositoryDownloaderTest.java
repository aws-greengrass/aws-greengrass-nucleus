/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.componentmanager.builtins;

import com.aws.greengrass.componentmanager.ComponentStore;
import com.aws.greengrass.componentmanager.ComponentTestResourceHelper;
import com.aws.greengrass.componentmanager.exceptions.PackageDownloadException;
import com.aws.greengrass.componentmanager.models.ComponentArtifact;
import com.aws.greengrass.componentmanager.models.ComponentIdentifier;
import com.aws.greengrass.componentmanager.models.RecipeMetadata;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.exceptions.RetryableServerErrorException;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
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
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.GetComponentVersionArtifactRequest;
import software.amazon.awssdk.services.greengrassv2data.model.GetComponentVersionArtifactResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import static com.aws.greengrass.componentmanager.builtins.GreengrassRepositoryDownloader.CONTENT_LENGTH_HEADER;
import static com.aws.greengrass.testcommons.testutilities.ExceptionLogProtector.ignoreExceptionOfType;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_PARTIAL;
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

@ExtendWith({GGExtension.class, MockitoExtension.class})
class GreengrassRepositoryDownloaderTest {
    private static final String SHA256 = "SHA-256";
    private static final String TEST_ARN = "arn";
    private static final String S3_ENDPOINT = "REGIONAL";
    @Captor
    ArgumentCaptor<GetComponentVersionArtifactRequest> getComponentVersionArtifactRequestArgumentCaptor;
    @Mock
    private ExecutableHttpRequest request;
    @Mock
    private SdkHttpClient httpClient;
    @Mock
    private GreengrassV2DataClient client;
    @Mock
    private GreengrassServiceClientFactory clientFactory;
    @Mock
    private ComponentStore componentStore;
    @Mock
    private DeviceConfiguration deviceConfiguration;
    @Mock
    Context context;

    @BeforeEach
    void beforeEach() throws Exception {
        lenient().when(clientFactory.fetchGreengrassV2DataClient()).thenReturn(client);
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
                spy(new GreengrassRepositoryDownloader(clientFactory, pkgId, artifact, saveToPath, componentStore, deviceConfiguration));

        assertThat(downloader.getArtifactFilename(), is("artifact.txt"));

        // mock requests to get downloadSize and local file name
        GetComponentVersionArtifactResponse result =
                GetComponentVersionArtifactResponse.builder()
                        .preSignedUrl("https://www.amazon.com/artifact.txt").build();
        when(client.getComponentVersionArtifact(getComponentVersionArtifactRequestArgumentCaptor.capture()))
                .thenReturn(result);

        // mock requests to return partial stream
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath))).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(HTTP_PARTIAL)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath))).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

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
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(), null, componentStore, deviceConfiguration));

        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());
        when(request.call()).thenThrow(IOException.class);

        downloader.setClientExceptionRetryConfig(RetryUtils.RetryConfig.builder().maxAttempt(2)
                .retryableExceptions(Arrays.asList(SdkClientException.class, IOException.class)).build());

        PackageDownloadException e = assertThrows(PackageDownloadException.class,
                () -> downloader.download(0, 100, MessageDigest.getInstance("SHA-256")));

        // assert retry called
        verify(request, times(2)).call();
        assertThat(e.getLocalizedMessage(), containsStringIgnoringCase("Failed to download artifact"));
    }

    @Test
    void GIVEN_get_download_size_response_as_5xx_WHEN_attempt_get_download_size_THEN_retry(ExtensionContext context) throws Exception {
        ignoreExceptionOfType(context, RetryableServerErrorException.class);
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
                spy(new GreengrassRepositoryDownloader(clientFactory, pkgId, artifact, saveToPath, componentStore, deviceConfiguration));

        downloader.setClientExceptionRetryConfig(
                downloader.getClientExceptionRetryConfig().toBuilder().initialRetryInterval(Duration.ZERO).build());
        assertThat(downloader.getArtifactFilename(), is("artifact.txt"));

        // mock requests to get downloadSize and local file name
        GetComponentVersionArtifactResponse result =
                GetComponentVersionArtifactResponse.builder()
                        .preSignedUrl("https://www.amazon.com/artifact.txt").build();
        when(client.getComponentVersionArtifact(getComponentVersionArtifactRequestArgumentCaptor.capture()))
                .thenReturn(result);

        // mock requests to return 500 error code
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(500).build())
                        .build())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath))).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

        downloader.getDownloadSize();
        assertNotEquals(Files.size(mockArtifactPath), -1);
        verify(httpClient, times(2)).prepareRequest(any());
        verify(request, times(2)).call();
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
                ComponentArtifact.builder().artifactUri(new URI("greengrass:binary")).build(), null, componentStore, deviceConfiguration));

        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());
        doReturn(HttpExecuteResponse.builder()
                .response(SdkHttpResponse.builder().statusCode(HTTP_BAD_REQUEST).build())
                .build()).when(request).call();

        PackageDownloadException e = assertThrows(PackageDownloadException.class,
                () -> downloader.download(0, 100, MessageDigest.getInstance("SHA-256")));

        // assert retry called
        verify(request, times(1)).call();
        assertThat(e.getLocalizedMessage(), containsStringIgnoringCase("Unable to download Greengrass artifact"));
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

    @Test
    void GIVEN_regionalS3Endpoint_WHEN_download_artifact_THEN_request_contains_regional_endpoint() throws Exception {
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

        Topic s3Endpoint = Topic.of(context, DeviceConfiguration.S3_ENDPOINT_TYPE,
                "REGIONAL");
        when(deviceConfiguration.gets3EndpointType()).thenReturn(s3Endpoint);
        GreengrassRepositoryDownloader downloader =
                spy(new GreengrassRepositoryDownloader(clientFactory, pkgId, artifact, saveToPath, componentStore, deviceConfiguration));

        // mock requests to get downloadSize and local file name
        GetComponentVersionArtifactResponse result =
                GetComponentVersionArtifactResponse.builder()
                        .preSignedUrl("https://www.amazon.com/artifact.txt").build();
        when(client.getComponentVersionArtifact(getComponentVersionArtifactRequestArgumentCaptor.capture()))
                .thenReturn(result);

        // mock requests to return partial stream
        doReturn(httpClient).when(downloader).getSdkHttpClient();
        doReturn(request).when(httpClient).prepareRequest(any());
        when(request.call())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(HTTP_OK)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath))).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build())
                .thenReturn(HttpExecuteResponse.builder()
                        .response(SdkHttpResponse.builder().statusCode(HTTP_PARTIAL)
                                .putHeader(CONTENT_LENGTH_HEADER, String.valueOf(Files.size(mockArtifactPath))).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(mockArtifactPath)))
                        .build());

        downloader.download();
        GetComponentVersionArtifactRequest generatedRequest =
                getComponentVersionArtifactRequestArgumentCaptor.getValue();
        assertEquals(S3_ENDPOINT, generatedRequest.s3EndpointTypeAsString());
    }
}
