/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.deployment.exceptions.DeploymentException;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.iot.IotCloudHelper;
import com.aws.greengrass.iot.IotConnectionManager;
import com.aws.greengrass.iot.model.IotCloudResponse;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.StandaloneMqttConnector;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.security.exceptions.MqttConnectionProviderException;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.RetryUtils;
import com.aws.greengrass.util.Utils;

import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_CONFIG_EVENT_KEY;
import static com.aws.greengrass.deployment.DeploymentConfigMerger.MERGE_ERROR_LOG_EVENT_KEY;

/**
 * Validates connectivity to new IoT endpoints before applying endpoint-switch deployments.
 * Performs MQTT connectivity checks and credential endpoint verification using the device's
 * existing certificate to ensure the target account/region is properly configured.
 */
class EndpointSwitchPreflightValidator {
    private static final Duration CREDENTIAL_CHECK_INITIAL_RETRY_INTERVAL = Duration.ofSeconds(1);
    private static final Duration CREDENTIAL_CHECK_MAX_RETRY_INTERVAL = Duration.ofSeconds(10);
    private static final String ENDPOINT_LOG_KEY = "endpoint";
    private static final String CREDENTIAL_URL_FORMAT = "https://%s/role-aliases/%s/credentials";

    private static final Logger logger = LogManager.getLogger(EndpointSwitchPreflightValidator.class);

    private final Kernel kernel;
    private final DeviceConfiguration deviceConfiguration;
    private final IotCloudHelper iotCloudHelper;
    private final IotConnectionManager iotConnectionManager;

    @Inject
    EndpointSwitchPreflightValidator(Kernel kernel, DeviceConfiguration deviceConfiguration,
                                     IotCloudHelper iotCloudHelper, IotConnectionManager iotConnectionManager) {
        this.kernel = kernel;
        this.deviceConfiguration = deviceConfiguration;
        this.iotCloudHelper = iotCloudHelper;
        this.iotConnectionManager = iotConnectionManager;
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(value = "REC_CATCH_EXCEPTION",
            justification = "Defensive catch for unexpected errors from CRT builder and proxy config")
    boolean verifyMqttConnectivity(CompletableFuture<DeploymentResult> totallyCompleteFuture,
                                   String endpoint, String clientIdSuffix, long timeoutMs) {
        try (StandaloneMqttConnector conn = StandaloneMqttConnector.of(
                kernel.getContext().get(SecurityService.class), deviceConfiguration,
                endpoint, clientIdSuffix)) {
            conn.connect(timeoutMs);
            return true;
        } catch (MqttConnectionProviderException e) {
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .kv(ENDPOINT_LOG_KEY, endpoint)
                    .log("Pre-flight MQTT connectivity check failed: device identity configuration error");
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new DeploymentException("Device identity configuration error", e,
                                    DeploymentErrorCode.TLS_HANDSHAKE_FAILURE)));
            return false;
        } catch (DeploymentException e) {
            if (e.getErrorCodes().contains(DeploymentErrorCode.MISSING_MQTT_CONNECT_POLICY)) {
                String thingName = Coerce.toString(deviceConfiguration.getThingName());
                logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                        .kv(ENDPOINT_LOG_KEY, endpoint)
                        .log("Pre-flight MQTT connectivity check failed: MQTT CONNECT rejected. "
                                + "Possible causes: (1) IoT policy does not allow client ID \""
                                + thingName + clientIdSuffix + "\" — update iot:Connect resource to "
                                + "\"arn:aws:iot:*:*:client/" + thingName + "*\", or "
                                + "(2) device certificate is not authorized in the target account");
            } else {
                logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                        .kv(ENDPOINT_LOG_KEY, endpoint)
                        .log("Pre-flight MQTT connectivity check failed");
            }
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            return false;
        } catch (Exception e) {
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .kv(ENDPOINT_LOG_KEY, endpoint)
                    .log("Pre-flight MQTT connectivity check failed");
            totallyCompleteFuture.complete(
                    new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                            new DeploymentException("MQTT pre-flight connection to " + endpoint + " failed",
                                    e, DeploymentErrorCode.MQTT_CONNECTION_FAILED)));
            return false;
        }
    }

    @SuppressWarnings({"PMD.AvoidCatchingGenericException"})
    boolean verifyCredentialEndpoint(CompletableFuture<DeploymentResult> totallyCompleteFuture,
                                     String credEndpoint, String roleAlias, long timeoutMs) {
        if (Utils.isEmpty(roleAlias)) {
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY)
                    .log("Pre-flight credential endpoint check failed: role alias is empty");
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                    new DeploymentException("Role alias must not be empty",
                            DeploymentErrorCode.CREDENTIAL_ENDPOINT_AUTH_FAILURE)));
            return false;
        }

        String thingName = Coerce.toString(deviceConfiguration.getThingName());
        String credUrl = String.format(CREDENTIAL_URL_FORMAT, credEndpoint, roleAlias);

        logger.atInfo().setEventType(MERGE_CONFIG_EVENT_KEY)
                .kv(ENDPOINT_LOG_KEY, credEndpoint)
                .kv("roleAlias", roleAlias)
                .log("Starting pre-flight credential endpoint check");

        int maxAttempts = Math.max(1, (int) (timeoutMs / CREDENTIAL_CHECK_MAX_RETRY_INTERVAL.toMillis()));
        RetryUtils.RetryConfig retryConfig = RetryUtils.RetryConfig.builder()
                .initialRetryInterval(CREDENTIAL_CHECK_INITIAL_RETRY_INTERVAL)
                .maxRetryInterval(CREDENTIAL_CHECK_MAX_RETRY_INTERVAL)
                .maxAttempt(maxAttempts)
                .retryableExceptions(Collections.singletonList(AWSIotException.class))
                .build();

        try {
            // Note: IotCloudHelper.sendHttpRequest() has its own internal retry (3x200ms),
            // so the effective behavior is nested retries for transient network errors.
            RetryUtils.runWithRetry(retryConfig, () -> {
                IotCloudResponse response = iotCloudHelper.sendHttpRequest(
                        iotConnectionManager, thingName, credUrl, "GET", null);
                int statusCode = response.getStatusCode();
                if (statusCode == HttpURLConnection.HTTP_OK) {
                    return null;
                }
                if (statusCode == HttpURLConnection.HTTP_UNAUTHORIZED
                        || statusCode == HttpURLConnection.HTTP_FORBIDDEN
                        || statusCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    throw new DeploymentException(
                            "Credential endpoint returned " + statusCode + " for role alias '"
                                    + roleAlias + "': " + response
                                    + ". Possible causes: (1) role alias does not exist in target region,"
                                    + " (2) certificate is not authorized for this role alias,"
                                    + " (3) IoT policy is missing iot:AssumeRoleWithCertificate permission",
                            DeploymentErrorCode.CREDENTIAL_ENDPOINT_AUTH_FAILURE);
                }
                // All other non-200 responses (429, 5xx, etc.) are retryable
                throw new AWSIotException("Credential endpoint returned " + statusCode);
            }, "verify-credential-endpoint", logger);

            logger.atInfo().setEventType(MERGE_CONFIG_EVENT_KEY)
                    .kv(ENDPOINT_LOG_KEY, credEndpoint)
                    .log("Pre-flight credential endpoint check passed");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.atWarn().setEventType(MERGE_CONFIG_EVENT_KEY)
                    .log("Pre-flight credential endpoint check interrupted");
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, null));
            return false;
        } catch (DeploymentException e) {
            // auth failure (401/403/404) — already has correct error code
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .kv(ENDPOINT_LOG_KEY, credEndpoint)
                    .kv("reason", e.getMessage())
                    .log("Pre-flight credential endpoint check failed");
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE, e));
            return false;
        } catch (Exception e) {
            // retries exhausted (AWSIotException from 5xx/network) — map to CREDENTIAL_ENDPOINT_SERVER_ERROR
            logger.atError().setEventType(MERGE_ERROR_LOG_EVENT_KEY).setCause(e)
                    .kv(ENDPOINT_LOG_KEY, credEndpoint)
                    .kv("reason", e.getMessage())
                    .log("Pre-flight credential endpoint check failed: retries exhausted");
            totallyCompleteFuture.complete(new DeploymentResult(
                    DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                    new DeploymentException("Credential endpoint check failed: " + credEndpoint, e,
                            DeploymentErrorCode.CREDENTIAL_ENDPOINT_SERVER_ERROR)));
            return false;
        }
    }
}
