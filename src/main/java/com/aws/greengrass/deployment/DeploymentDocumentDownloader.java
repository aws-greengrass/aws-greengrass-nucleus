/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.amazon.aws.iot.greengrass.configuration.common.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.deployment.converter.DeploymentDocumentConverter;
import com.aws.greengrass.deployment.exceptions.DeploymentDocumentDownloadException;
import com.aws.greengrass.deployment.model.DeploymentDocument;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.network.HttpClientProvider;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Digest;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.SerializerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.lang3.StringUtils;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.services.greengrassv2data.model.GetDeploymentConfigurationRequest;
import software.amazon.awssdk.services.greengrassv2data.model.GetDeploymentConfigurationResponse;
import software.amazon.awssdk.utils.IoUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.NoSuchAlgorithmException;
import javax.inject.Inject;

public class DeploymentDocumentDownloader {
    private static final Logger logger = LogManager.getLogger(DeploymentDocumentDownloader.class);

    private final GreengrassServiceClientFactory greengrassServiceClientFactory;
    private final HttpClientProvider httpClientProvider;
    private final Topic thingNameTopic;


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
        this.thingNameTopic = deviceConfiguration.getThingName();
        this.httpClientProvider = httpClientProvider;
    }

    /**
     * Download the full deployment document stored from greengrass cloud.
     *
     * @param deploymentId deployment id
     * @return DeploymentDocument
     * @throws DeploymentDocumentDownloadException if failed to download the full deployment document.
     */
    public DeploymentDocument download(String deploymentId) throws DeploymentDocumentDownloadException {
        // TODO [Next Revision] Support downloading retrials for intermittent network and temporary GG data plane
        //  service error with RetryUtils

        // 1. Get url, digest, and algorithm by calling gg data plane
        GetDeploymentConfigurationResponse response = getDeploymentConfiguration(deploymentId);

        String preSignedUrl = response.preSignedUrl();
        String algorithm = response.integrityCheck().algorithm();
        String digest = response.integrityCheck().digest();

        validate(preSignedUrl, algorithm, digest);

        // 2. Download configuration and check integrity
        String configurationInString = downloadFromUrl(deploymentId, preSignedUrl);
        checkIntegrity(digest, configurationInString);

        // 3. deserialize and convert to device model
        Configuration configuration = deserializeDeploymentDoc(configurationInString);
        return DeploymentDocumentConverter.convertFromDeploymentConfiguration(configuration);

    }

    private String downloadFromUrl(String deploymentId, String preSignedUrl)
            throws DeploymentDocumentDownloadException {
        HttpExecuteRequest executeRequest = HttpExecuteRequest.builder()
                .request(SdkHttpFullRequest.builder().uri(URI.create(preSignedUrl)).method(SdkHttpMethod.GET).build())
                .build();

        logger.atInfo().kv("DeploymentId", deploymentId)
                .log("Making HTTP request to the presigned url");  // url is not logged for security concerns

        try (SdkHttpClient client = httpClientProvider.getSdkHttpClient()) {

            HttpExecuteResponse executeResponse;
            try {
                executeResponse = client.prepareRequest(executeRequest).call();
            } catch (IOException e) {
                throw new DeploymentDocumentDownloadException("I/O error when making HTTP request with presigned url.",
                        e);
            }

            validateHttpExecuteResponse(executeResponse);

            try (InputStream in = executeResponse.responseBody().get()) { // checked in validateResponse
                // load directly into memory because it will be used for resolving configuration
                return IoUtils.toUtf8String(in);
            } catch (IOException e) {
                throw new DeploymentDocumentDownloadException(
                        "I/O error when reading from HTTP response payload stream.", e);
            }
        }
    }

    private GetDeploymentConfigurationResponse getDeploymentConfiguration(String deploymentId)
            throws DeploymentDocumentDownloadException {
        String thingName = Coerce.toString(thingNameTopic.getOnce());
        if (thingName == null) {
            // could happen if thing name gets removed/lost after initial provisioning
            throw new DeploymentDocumentDownloadException("Can't contact Greengrass cloud because thing name is null.");
        }

        GetDeploymentConfigurationRequest getDeploymentConfigurationRequest =
                GetDeploymentConfigurationRequest.builder().deploymentId(deploymentId).coreDeviceThingName(thingName)
                        .build();

        GetDeploymentConfigurationResponse deploymentConfiguration;

        try {
            logger.atInfo().kv("DeploymentId", deploymentId).kv("ThingName", thingName)
                    .log("Calling Greengrass cloud to get full deployment configuration.");

            deploymentConfiguration = greengrassServiceClientFactory.getGreengrassV2DataClient()
                    .getDeploymentConfiguration(getDeploymentConfigurationRequest);
        } catch (AwsServiceException e) {
            throw new DeploymentDocumentDownloadException(
                    "Greengrass Cloud Service returned an error when getting full deployment configuration.", e);
        } catch (SdkClientException e) {
            throw new DeploymentDocumentDownloadException(
                    "Failed to contact Greengrass cloud or unable to parse response.", e);
        }
        return deploymentConfiguration;
    }

    private void validateHttpExecuteResponse(HttpExecuteResponse executeResponse)
            throws DeploymentDocumentDownloadException {
        if (!executeResponse.httpResponse().isSuccessful()) {
            throw new DeploymentDocumentDownloadException(String.format(
                    "Received unsuccessful HTTP status: [%s] when getting from preSigned url. Status Text: '%s'.",
                    executeResponse.httpResponse().statusCode(),
                    executeResponse.httpResponse().statusText().orElse(StringUtils.EMPTY)));
        }

        if (!executeResponse.responseBody().isPresent()) {
            throw new DeploymentDocumentDownloadException("Received empty response body.");
        }
    }

    private Configuration deserializeDeploymentDoc(String configurationInString)
            throws DeploymentDocumentDownloadException {
        Configuration configuration;
        try {
            configuration = SerializerFactory.getFailSafeJsonObjectMapper()
                    .readValue(configurationInString, Configuration.class);

        } catch (JsonProcessingException e) {
            throw new DeploymentDocumentDownloadException("Failed to deserialize error the deployment document JSON.",
                    e);
        }
        return configuration;
    }

    private void checkIntegrity(String digest, String configurationInString)
            throws DeploymentDocumentDownloadException {
        try {
            String calculatedDigest = Digest.calculate(configurationInString);
            if (!calculatedDigest.equals(digest)) {
                throw new DeploymentDocumentDownloadException(String.format(
                        "Integrity check failed because the calculated digest is different from provided digest.%n"
                                + "Provided digest: '%s'. %nCalculated digest: '%s'.", digest, calculatedDigest));
            }
        } catch (NoSuchAlgorithmException e) {
            // This should never happen as SHA-256 is mandatory for every default JVM provider
            throw new DeploymentDocumentDownloadException("No security provider found for message digest", e);
        }
    }

    private void validate(String preSignedUrl, String algorithm, String digest)
            throws DeploymentDocumentDownloadException {

        if (StringUtils.isBlank(preSignedUrl)) {
            throw new DeploymentDocumentDownloadException("preSignedUrl can't be null or blank");
        }
        if (StringUtils.isBlank(algorithm)) {
            throw new DeploymentDocumentDownloadException("algorithm can't be null or blank");
        }
        if (StringUtils.isBlank(digest)) {
            throw new DeploymentDocumentDownloadException("digest can't be null or blank");
        }

        if (!Digest.SHA_256.equals(algorithm)) {
            // Unsupported integrity check algorithm
            throw new DeploymentDocumentDownloadException(
                    String.format("Unsupported integrity check algorithm: '%s'. Supported algorithm: '%s'.", algorithm,
                            Digest.SHA_256));
        }
    }
}
