/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.componentmanager.exceptions.HashingAlgorithmUnavailableException;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.DeploymentTaskFailureException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.exceptions.InvalidRequestException;
import com.aws.greengrass.deployment.exceptions.RetryableClientErrorException;
import com.aws.greengrass.deployment.exceptions.RetryableDeploymentDocumentDownloadException;
import com.aws.greengrass.deployment.exceptions.RetryableServerErrorException;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.network.HttpClientProvider;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.SerializerFactory;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.HttpStatusCode;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.greengrassv2data.model.GetDeploymentConfigurationRequest;
import software.amazon.awssdk.services.greengrassv2data.model.GetDeploymentConfigurationResponse;
import software.amazon.awssdk.services.greengrassv2data.model.GreengrassV2DataException;
import software.amazon.awssdk.utils.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.inject.Inject;

import static org.apache.commons.io.FileUtils.ONE_MB;
import static software.amazon.awssdk.services.s3.checksums.ChecksumConstant.CONTENT_LENGTH_HEADER;

public class DeploymentDocumentDownloader {
    private static final Logger logger = LogManager.getLogger(DeploymentDocumentDownloader.class);
    private static final long MAX_DEPLOYMENT_DOCUMENT_SIZE_BYTES = 10 * ONE_MB;
    private static final int MAX_CLIENT_ERROR_RETRY_COUNT = 10;
    private final GreengrassServiceClientFactory greengrassServiceClientFactory;
    private final HttpClientProvider httpClientProvider;
    private final DeviceConfiguration deviceConfiguration;
    @Setter(AccessLevel.PACKAGE)
    @Getter(AccessLevel.PACKAGE)
    private RetryUtils.DifferentiatedRetryConfig clientExceptionRetryConfig;

    /**
     * Constructor.
     *
     * @param greengrassServiceClientFactory greengrassServiceClientFactory for lazily initialize the client.
     * @param deviceConfiguration            deviceConfiguration for getting the thing name topic.
     * @param httpClientProvider             httpClientProvider for making calls to presigned url.
     */
    @Inject
    public DeploymentDocumentDownloader(GreengrassServiceClientFactory greengrassServiceClientFactory,
                                        DeviceConfiguration deviceConfiguration,
                                        HttpClientProvider httpClientProvider) {
        this.greengrassServiceClientFactory = greengrassServiceClientFactory;
        this.deviceConfiguration = deviceConfiguration;
        this.httpClientProvider = httpClientProvider;

        Map<Set<Class>, Integer> retryMap = new HashMap<>();
        retryMap.put(Collections.singleton(RetryableClientErrorException.class), MAX_CLIENT_ERROR_RETRY_COUNT);
        Set<Class> serverErrorClasses = new HashSet<>();
        serverErrorClasses.add(RetryableDeploymentDocumentDownloadException.class);
        serverErrorClasses.add(DeviceConfigurationException.class);
        serverErrorClasses.add(RetryableServerErrorException.class);
        retryMap.put(serverErrorClasses, Integer.MAX_VALUE);

        this.clientExceptionRetryConfig = RetryUtils.DifferentiatedRetryConfig.builder()
                .initialRetryInterval(Duration.ofMinutes(1))
                .maxRetryInterval(Duration.ofMinutes(1))
                .retryMap(retryMap)
                .build();
    }

    /**
     * Download the full deployment document stored from greengrass cloud.
     *
     * @param deploymentId deployment id
     * @return DeploymentDocument
     * @throws DeploymentTaskFailureException if failed to download the full deployment document.
     * @throws InterruptedException if interrupted.
     */
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.AvoidRethrowingException"})
    public DeploymentDocument download(String deploymentId)
            throws InterruptedException, DeploymentTaskFailureException {
        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            throw new DeploymentTaskFailureException(
                    "Device not configured to talk to cloud, cannot download deployment document",
                    DeploymentErrorCode.NETWORK_ERROR);
        }

        String configurationString;
        try {
            configurationString = RetryUtils.runWithRetry(clientExceptionRetryConfig,
                    () -> downloadDeploymentDocument(deploymentId), "download-large-configuration",
                    logger);
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            throw new DeploymentTaskFailureException("Error downloading deployment configuration", e);
        }
        // 3. deserialize and convert to device model
        return deserializeDeploymentDoc(configurationString);
    }

    protected String downloadDeploymentDocument(String deploymentId)
            throws DeploymentTaskFailureException, RetryableDeploymentDocumentDownloadException,
            DeviceConfigurationException, HashingAlgorithmUnavailableException, RetryableServerErrorException,
            RetryableClientErrorException {
        // 1. Get url, digest, and algorithm by calling gg data plane
        GetDeploymentConfigurationResponse response = getDeploymentConfiguration(deploymentId);

        String preSignedUrl = response.preSignedUrl();
        String algorithm = response.integrityCheck().algorithm();
        String digest = response.integrityCheck().digest();

        validate(preSignedUrl, algorithm, digest);

        // 2. Download configuration and check integrity.
        String configurationInString = downloadFromUrl(deploymentId, preSignedUrl);
        checkIntegrity(algorithm, digest, configurationInString);
        return configurationInString;
    }

    private String downloadFromUrl(String deploymentId, String preSignedUrl)
            throws RetryableDeploymentDocumentDownloadException, DeploymentTaskFailureException {
        HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                .request(SdkHttpFullRequest.builder().uri(URI.create(preSignedUrl)).method(SdkHttpMethod.GET).build())
                .build();

        // url is not logged for security concerns
        logger.atDebug().kv("DeploymentId", deploymentId)
                .log("Making HTTP request to the presigned url");

        try (SdkHttpClient client = httpClientProvider.getSdkHttpClient()) {

            HttpExecuteResponse executeResponse;
            try {
                executeResponse = client.prepareRequest(executeRequest).call();
            } catch (IOException e) {
                throw new RetryableDeploymentDocumentDownloadException(
                        "I/O error when making HTTP request with presigned url.", e)
                        .withErrorContext(e, DeploymentErrorCode.HTTP_REQUEST_ERROR);
            }

            validateHttpExecuteResponse(executeResponse);

            try (InputStream in = executeResponse.responseBody().get()) { // checked in validateResponse
                // load directly into memory because it will be used for resolving configuration
                return IoUtils.toUtf8String(in);
            } catch (IOException e) {
                throw new RetryableDeploymentDocumentDownloadException(
                        "I/O error when reading from HTTP response payload stream.", e)
                        .withErrorContext(e, DeploymentErrorCode.IO_READ_ERROR);
            }
        }
    }

    private GetDeploymentConfigurationResponse getDeploymentConfiguration(String deploymentId)
            throws RetryableDeploymentDocumentDownloadException, DeviceConfigurationException,
            DeploymentTaskFailureException, RetryableServerErrorException, RetryableClientErrorException {
        String thingName = Coerce.toString(deviceConfiguration.getThingName());
        GetDeploymentConfigurationRequest getDeploymentConfigurationRequest =
                GetDeploymentConfigurationRequest.builder().deploymentId(deploymentId).coreDeviceThingName(thingName)
                        .s3EndpointType(Coerce.toString(deviceConfiguration.gets3EndpointType())).build();

        GetDeploymentConfigurationResponse deploymentConfiguration;

        try {
            logger.atInfo().kv("DeploymentId", deploymentId).kv("ThingName", thingName)
                    .log("Calling Greengrass cloud to get full deployment configuration");

            deploymentConfiguration = greengrassServiceClientFactory.fetchGreengrassV2DataClient()
                            .getDeploymentConfiguration(getDeploymentConfigurationRequest);

        } catch (GreengrassV2DataException e) {
            if (RetryUtils.retryErrorCodes(e.statusCode())) {
                throw new RetryableServerErrorException("Failed with retryable error: " + e.statusCode()
                        + " while calling getDeploymentConfiguration", e);
            }
            // also retry on 404s because sometimes querying DDB may fail initially due to its eventual consistency
            if (e.statusCode() == HttpStatusCode.NOT_FOUND) {
                throw new RetryableClientErrorException("Failed with retryable error: " + e.statusCode()
                        + " while calling getDeploymentConfiguration", e);
            }
            if (e.statusCode() == HttpStatusCode.FORBIDDEN)  {
                throw new DeploymentTaskFailureException(
                        "Access denied when calling GetDeploymentConfiguration. Ensure "
                                + "certificate policy grants greengrass:GetDeploymentConfiguration",
                        e).withErrorContext(e, DeploymentErrorCode.GET_DEPLOYMENT_CONFIGURATION_ACCESS_DENIED);
            }
            throw new DeploymentTaskFailureException("Error while calling GetDeploymentConfiguration", e);
        } catch (AwsServiceException e) {
            throw new RetryableDeploymentDocumentDownloadException(
                    "Greengrass Cloud Service returned an error when getting full deployment configuration", e);
        } catch (SdkClientException e) {
            throw new RetryableDeploymentDocumentDownloadException(
                    "Failed to contact Greengrass cloud or unable to parse response", e);
        }
        return deploymentConfiguration;
    }

    private void validateHttpExecuteResponse(HttpExecuteResponse executeResponse)
            throws RetryableDeploymentDocumentDownloadException, DeploymentTaskFailureException {
        if (!executeResponse.httpResponse().isSuccessful()) {
            throw new RetryableDeploymentDocumentDownloadException(String.format(
                    "Received unsuccessful HTTP status: [%s] when getting from preSigned url. Status Text: '%s'",
                    executeResponse.httpResponse().statusCode(),
                    executeResponse.httpResponse().statusText().orElse(StringUtils.EMPTY)));
        }
        Optional<String> deploymentDocumentSizeOptional = executeResponse.httpResponse()
                .firstMatchingHeader(CONTENT_LENGTH_HEADER);

        //this should never happen as GGC cloud supports max 10 MB documents due to API GW payload limit,
        //but adding a check as deployment document is read into process memory.
        if (deploymentDocumentSizeOptional.isPresent()
                && Long.parseLong(deploymentDocumentSizeOptional.get()) > MAX_DEPLOYMENT_DOCUMENT_SIZE_BYTES) {
            throw new DeploymentTaskFailureException(String.format("Requested deployment document exceeded size limit."
                    + " The requested document is %s bytes, but the size limit is %s bytes",
                    deploymentDocumentSizeOptional.get(), MAX_DEPLOYMENT_DOCUMENT_SIZE_BYTES),
                    DeploymentErrorCode.DEPLOYMENT_DOCUMENT_SIZE_EXCEEDED);
        }

        if (!executeResponse.responseBody().isPresent()) {
            throw new RetryableDeploymentDocumentDownloadException("Received empty response body");
        }
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private DeploymentDocument deserializeDeploymentDoc(String configurationInString)
            throws DeploymentTaskFailureException {
        try {
            Configuration configuration =  SerializerFactory.getFailSafeJsonObjectMapper()
                    .readValue(configurationInString, Configuration.class);
            return DeploymentDocumentConverter.convertFromDeploymentConfiguration(configuration);
        } catch (IOException e) {
            throw new DeploymentTaskFailureException("Failed to deserialize deployment document", e)
                    .withErrorContext(e, DeploymentErrorCode.DEPLOYMENT_DOCUMENT_PARSE_ERROR);
        } catch (InvalidRequestException e) {
            throw new DeploymentTaskFailureException("Invalid component metadata from deployment document",
                    e).withErrorContext(e, DeploymentErrorCode.COMPONENT_METADATA_NOT_VALID_IN_DEPLOYMENT);
        }

    }

    private void checkIntegrity(String algorithm, String digest, String configurationInString)
            throws RetryableDeploymentDocumentDownloadException, HashingAlgorithmUnavailableException {
        try {
            String calculatedDigest = Digest.calculate(algorithm, configurationInString);
            if (!calculatedDigest.equals(digest)) {
                throw new RetryableDeploymentDocumentDownloadException(String.format(
                        "Integrity check failed because the calculated digest is different from provided digest.%n"
                                + "Provided digest: '%s'. %nCalculated digest: '%s'", digest, calculatedDigest));
            }
        } catch (NoSuchAlgorithmException e) {
            // This should never happen as SHA-256 is mandatory for every default JVM provider
            throw new HashingAlgorithmUnavailableException("No security provider found for message digest", e);
        }
    }

    private void validate(String preSignedUrl, String algorithm, String digest)
            throws RetryableDeploymentDocumentDownloadException {

        if (Utils.isEmpty(preSignedUrl)) {
            throw new RetryableDeploymentDocumentDownloadException("preSignedUrl can't be null or blank");
        }
        if (Utils.isEmpty(algorithm)) {
            throw new RetryableDeploymentDocumentDownloadException("algorithm can't be null or blank");
        }
        if (Utils.isEmpty(digest)) {
            throw new RetryableDeploymentDocumentDownloadException("digest can't be null or blank");
        }
    }
}
