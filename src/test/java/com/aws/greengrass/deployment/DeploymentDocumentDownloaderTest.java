/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.RetryableDeploymentDocumentDownloadException;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.network.HttpClientProvider;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.SerializerFactory;
import org.apache.commons.io.IOUtils;
import org.apache.http.conn.ConnectTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.GetDeploymentConfigurationRequest;
import software.amazon.awssdk.services.greengrassv2data.model.GetDeploymentConfigurationResponse;
import software.amazon.awssdk.services.greengrassv2data.model.IntegrityCheck;
import software.amazon.awssdk.utils.IoUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.core.StringContains.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class DeploymentDocumentDownloaderTest {
    private static final String THING_NAME = "myThing";
    private static final String DEPLOYMENT_ID = "deploymentId";

    private final Context context = new Context();
    private final Topic thingNameTopic = Topic.of(context, "thingName", THING_NAME);

    @Mock
    private GreengrassServiceClientFactory greengrassServiceClientFactory;

    @Mock
    private GreengrassV2DataClient greengrassV2DataClient;

    @Mock
    private DeviceConfiguration deviceConfiguration;

    @Mock
    private HttpClientProvider httpClientProvider;

    @Mock
    private SdkHttpClient httpClient;

    @Mock
    private ExecutableHttpRequest request;

    private DeploymentDocumentDownloader downloader;

    @BeforeEach
    void beforeEach() {
        when(greengrassServiceClientFactory.getGreengrassV2DataClient()).thenReturn(greengrassV2DataClient);
        lenient().when(deviceConfiguration.isDeviceConfiguredToTalkToCloud()).thenReturn(true);
        when(deviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        downloader = new DeploymentDocumentDownloader(greengrassServiceClientFactory, deviceConfiguration,
                httpClientProvider);
    }

    @AfterEach
    void afterEach() throws IOException {
        context.close();
    }

    @Test
    void GIVEN_a_valid_doc_being_returned_as_http_response_WHEN_download_THEN_doc_is_downloaded_and_converted()
            throws Exception {
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);

        Path testFcsDeploymentJsonPath =
                Paths.get(this.getClass().getResource("converter").toURI()).resolve("FcsDeploymentConfig_Full.json");

        String expectedDeployConfigStr = IoUtils.toUtf8String(Files.newInputStream(testFcsDeploymentJsonPath));
        String expectedDigest = Digest.calculate(expectedDeployConfigStr);

        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest(expectedDigest).build())
                        .build());

        // mock http client to return the file content
        when(httpClient.prepareRequest(any())).thenReturn(request);

        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_OK).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(testFcsDeploymentJsonPath)))
                        .build());

        DeploymentDocument deploymentDocumentOptional = downloader.download(DEPLOYMENT_ID);

        DeploymentDocument expectedDeploymentDoc = DeploymentDocumentConverter.convertFromDeploymentConfiguration(
                SerializerFactory.getFailSafeJsonObjectMapper()
                        .readValue(expectedDeployConfigStr, Configuration.class));

        assertThat(deploymentDocumentOptional, equalTo(expectedDeploymentDoc));

        // verify
        verify(greengrassV2DataClient).getDeploymentConfiguration(
                GetDeploymentConfigurationRequest.builder().deploymentId(DEPLOYMENT_ID).coreDeviceThingName(THING_NAME)
                        .build());
    }

    @Test
    void GIVEN_thing_name_changed_WHEN_download_THEN_updated_thing_name_is_used() throws Exception {
        // change thing name
        String newThingName = "newThingName";
        thingNameTopic.withValue(newThingName);
        thingNameTopic.context.waitForPublishQueueToClear(); // so that newThingName is published.

        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);

        Path testFcsDeploymentJsonPath =
                Paths.get(this.getClass().getResource("converter").toURI()).resolve("FcsDeploymentConfig_Full.json");

        String expectedDeployConfigStr = IoUtils.toUtf8String(Files.newInputStream(testFcsDeploymentJsonPath));
        String expectedDigest = Digest.calculate(expectedDeployConfigStr);

        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest(expectedDigest).build())
                        .build());

        // mock http client to return the file content
        when(httpClient.prepareRequest(any())).thenReturn(request);

        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_OK).build())
                        .responseBody(AbortableInputStream.create(Files.newInputStream(testFcsDeploymentJsonPath)))
                        .build());

        DeploymentDocument deploymentDocument = downloader.download(DEPLOYMENT_ID);

        DeploymentDocument expectedDeploymentDoc = DeploymentDocumentConverter.convertFromDeploymentConfiguration(
                SerializerFactory.getFailSafeJsonObjectMapper()
                        .readValue(expectedDeployConfigStr, Configuration.class));

        assertThat(deploymentDocument, equalTo(expectedDeploymentDoc));

        // verify
        verify(greengrassV2DataClient).getDeploymentConfiguration(
                GetDeploymentConfigurationRequest.builder().deploymentId(DEPLOYMENT_ID)
                        .coreDeviceThingName(newThingName).build());
    }


    @Test
    void GIVEN_gg_client_throws_AwsServiceException_WHEN_download_THEN_throws_with_proper_message() {
        // mock gg client to throw AwsServiceException
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenThrow(AwsServiceException.builder().build());
        verifyNoInteractions(httpClient);

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class,
                        () -> downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString(
                "Greengrass Cloud Service returned an error when getting full deployment configuration."));
    }

    @Test
    void GIVEN_gg_client_throws_SdkClientException_WHEN_download_THEN_throws_with_proper_message() {
        // mock gg client to throw SdkClientException
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenThrow(SdkClientException.builder().build());
        verifyNoInteractions(httpClient);

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class,
                        () -> downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(),
                containsString("Failed to contact Greengrass cloud or unable to parse response."));
    }

    @Test
    void GIVEN_get_pre_signed_url_call_throws_exception_WHEN_download_THEN_throws_with_proper_message()
            throws IOException {
        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest("digest").build())
                        .build());

        // mock http client to throw exception when calling
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);
        when(httpClient.prepareRequest(any())).thenReturn(request);
        when(request.call()).thenThrow(new ConnectTimeoutException());

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class,
                        () -> downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString("I/O error when making HTTP request with presigned url"));
    }

    @Test
    void GIVEN_get_pre_signed_url_returns_non_successful_http_status_WHEN_download_THEN_throw_with_proper_message()
            throws Exception {
        // mock gg client
        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest("digest").build())
                        .build());

        // mock http client to return the test file
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);
        when(httpClient.prepareRequest(any())).thenReturn(request);

        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_BAD_REQUEST).build())
                        .build());

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class,
                        () -> downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString("Received unsuccessful HTTP status"));
    }

    @Test
    void GIVEN_get_pre_signed_url_returns_empty_body_WHEN_download_THEN_throw_with_proper_message() throws Exception {
        // mock gg client
        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest("digest").build())
                        .build());

        // mock http client to return the test file
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);
        when(httpClient.prepareRequest(any())).thenReturn(request);

        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_OK).build())
                        .build()); // empty body

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class,
                        () -> downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString("Received empty response body"));
    }

    @Test
    @SuppressWarnings("PMD.CloseResource")
    void GIVEN_get_pre_signed_url_returns_problematic_stream_WHEN_download_throw_with_proper_message()
            throws Exception {
        // mock gg client
        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest("digest").build())
                        .build());

        // mock http client to return the test file
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);
        when(httpClient.prepareRequest(any())).thenReturn(request);

        AbortableInputStream mockInputStream = mock(AbortableInputStream.class);
        when(mockInputStream.read(any())).thenThrow(new IOException());
        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_OK).build())
                        .responseBody(mockInputStream) // mock throws IOException
                        .build());

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class, () ->
                        downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString("I/O error when reading from HTTP response payload stream"));
    }

    @Test
    void GIVEN_gg_cloud_returns_different_digest_then_downloaded_content_WHEN_download_THEN_throws_with_proper_message()
            throws Exception {
        String url = "https://www.presigned.com/a.json";
        String expectedDigest = "digest";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest(expectedDigest).build())
                        .build());

        // mock http client to return some random content so that digest is different
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);
        when(httpClient.prepareRequest(any())).thenReturn(request);

        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_OK).build())
                        .responseBody(AbortableInputStream.create(IOUtils.toInputStream("random"))).build());

        RetryableDeploymentDocumentDownloadException exception =
                assertThrows(RetryableDeploymentDocumentDownloadException.class, () ->
                        downloader.downloadDeploymentDocument(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString(
                "Integrity check failed because the calculated digest is different from provided digest"));
    }

    @Test
    void GIVEN_download_content_with_invalid_format_WHEN_download_THEN_throws_with_proper_message() throws Exception {
        when(httpClientProvider.getSdkHttpClient()).thenReturn(httpClient);

        String inValidDoc = "I'm not even a JSON.";
        String expectedDigest = Digest.calculate(inValidDoc);

        String url = "https://www.presigned.com/a.json";

        // mock gg client
        when(greengrassV2DataClient.getDeploymentConfiguration(Mockito.any(GetDeploymentConfigurationRequest.class)))
                .thenReturn(GetDeploymentConfigurationResponse.builder().preSignedUrl(url)
                        .integrityCheck(IntegrityCheck.builder().algorithm("SHA-256").digest(expectedDigest).build())
                        .build());

        // mock http client to return the test file
        when(httpClient.prepareRequest(any())).thenReturn(request);

        when(request.call()).thenReturn(
                HttpExecuteResponse.builder().response(SdkHttpResponse.builder().statusCode(HTTP_OK).build())
                        .responseBody(AbortableInputStream.create(IOUtils.toInputStream(inValidDoc))).build());

        DeploymentTaskFailureException exception =
                assertThrows(DeploymentTaskFailureException.class,
                        () -> downloader.download(DEPLOYMENT_ID));

        assertThat(exception.getMessage(), containsString("Failed to deserialize deployment document."));
    }
}