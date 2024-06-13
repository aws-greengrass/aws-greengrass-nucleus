/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.errorcode.DeploymentErrorCode;
import com.aws.greengrass.deployment.exceptions.ComponentConfigurationValidationException;
import com.aws.greengrass.deployment.model.DeploymentResult;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.security.SecurityService;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.GreengrassServiceClientFactory;
import com.aws.greengrass.util.Pair;
import lombok.AccessLevel;
import lombok.Setter;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.services.greengrassv2data.GreengrassV2DataClient;
import software.amazon.awssdk.services.greengrassv2data.model.ListThingGroupsForCoreDeviceRequest;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ConnectivityValidator {
    private static final Logger logger = LogManager.getLogger(ConnectivityValidator.class);
    public static final String CLIENT_ID_SUFFIX = "#validation";
    private static final String WITH_NEW_CONFIG_MESSAGE_SUFFIX = " with the new configuration";
    private final MqttClient mqttClient;
    private final GreengrassServiceClientFactory factory;
    private final SecurityService securityService;
    @Setter(AccessLevel.PACKAGE)  // for unit tests
    private DeviceConfiguration deploymentConfiguration;

    /**
     * Constructor.
     *
     * @param kernel           kernel to get current config and context from
     * @param context          temporary context used for deployment configuration
     * @param mqttClient       Used to create AWS Mqtt Connection Client
     * @param factory          Used to create Greengrass data client
     * @param securityService  Used for Mqtt connection builder
     * @param deploymentConfig Map of the configs to be merged in and validated against
     * @param timestamp        time stamp of the deployment
     * @throws InterruptedException if interrupted merging deployment config
     */
    public ConnectivityValidator(Kernel kernel, Context context, MqttClient mqttClient,
                                 GreengrassServiceClientFactory factory, SecurityService securityService,
                                 Map<String, Object> deploymentConfig, long timestamp) throws InterruptedException {
        this.mqttClient = mqttClient;
        this.factory = factory;
        this.securityService = securityService;

        Configuration config = new Configuration(context);
        // Copy the current device configuration Map to preserve time stamps
        config.copyFrom(kernel.getConfig());
        // Attempt to merge the deployment configs using the deployment time stamp
        config.mergeMap(timestamp, deploymentConfig);
        try {
            config.waitConfigUpdateComplete();
        } catch (InterruptedException e) {
            logger.atInfo().log("Interrupted while waiting for deployment config update to complete");
            Thread.currentThread().interrupt();
            throw e;
        }
        deploymentConfiguration = new DeviceConfiguration(config, kernel.getKernelCommandLine());
        /*
         * Need to set security service due to plugin dependency workaround
         * after removing Kernel from DeviceConfiguration
         */
        deploymentConfiguration.setSecurityService(securityService);
    }

    private boolean mqttClientNeedsValidation(DeviceConfiguration currentDeviceConfiguration) {
        return networkPoxyHasChanged(currentDeviceConfiguration) || awsRegionHasChanged(currentDeviceConfiguration)
                || mqttHasChanged(currentDeviceConfiguration) || iotDataEndpointHasChanged(currentDeviceConfiguration);
    }

    private boolean httpClientNeedsValidation(DeviceConfiguration currentDeviceConfiguration) {
        return networkPoxyHasChanged(currentDeviceConfiguration) || awsRegionHasChanged(currentDeviceConfiguration)
                || greengrassDataPlaneEndpointHasChanged(currentDeviceConfiguration)
                || greengrassDataPlanePortHasChanged(currentDeviceConfiguration);
    }

    private boolean networkPoxyHasChanged(DeviceConfiguration currentdeviceConfiguration) {
        Topics currentNetworkProxy = currentdeviceConfiguration.getNetworkProxyNamespace();
        Topics newNetworkProxy = deploymentConfiguration.getNetworkProxyNamespace();
        return !Topics.compareChildren(currentNetworkProxy, newNetworkProxy);
    }

    private boolean awsRegionHasChanged(DeviceConfiguration currentdeviceConfiguration) {
        // Defaults to empty string topic
        Topic currentAwsRegion = currentdeviceConfiguration.getAWSRegion();
        Topic newAwsRegion = deploymentConfiguration.getAWSRegion();
        return !Topic.compareValue(currentAwsRegion, newAwsRegion);
    }

    private boolean mqttHasChanged(DeviceConfiguration currentdeviceConfiguration) {
        Topics currentMqtt = currentdeviceConfiguration.getMQTTNamespace();
        Topics newMqtt = deploymentConfiguration.getMQTTNamespace();
        return !Topics.compareChildren(currentMqtt, newMqtt);
    }

    private boolean iotDataEndpointHasChanged(DeviceConfiguration currentdeviceConfiguration) {
        // Defaults to empty string topic
        Topic currentIotDataEndpoint = currentdeviceConfiguration.getIotDataEndpoint();
        Topic newIotDataEndpoint = deploymentConfiguration.getIotDataEndpoint();
        return !Topic.compareValue(currentIotDataEndpoint, newIotDataEndpoint);
    }

    private boolean greengrassDataPlaneEndpointHasChanged(DeviceConfiguration currentdeviceConfiguration) {
        // Defaults to empty string topic
        Topic currentGreengrassDataPlaneEndpoint = currentdeviceConfiguration.getGGDataEndpoint();
        Topic newGreengrassDataPlaneEndpoint = deploymentConfiguration.getGGDataEndpoint();
        return !Topic.compareValue(currentGreengrassDataPlaneEndpoint, newGreengrassDataPlaneEndpoint);
    }

    private boolean greengrassDataPlanePortHasChanged(DeviceConfiguration currentdeviceConfiguration) {
        Topic currentGreengrassDataPlanePort = currentdeviceConfiguration.getGreengrassDataPlanePort();
        Topic newGreengrassDataPlanePort = deploymentConfiguration.getGreengrassDataPlanePort();
        return !Topic.compareValue(currentGreengrassDataPlanePort, newGreengrassDataPlanePort);
    }

    /**
     * Creates an MQTT client and checks if the device can connect to AWS.
     *
     * @param totallyCompleteFuture Future will be updated if validation fails
     */
    public boolean mqttClientCanConnect(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        logger.atDebug().log("Checking MQTT client can connect");

        MqttClientConnection connection = null;
        String message;
        Throwable cause;
        try (AwsIotMqttConnectionBuilder builder = mqttClient.createMqttConnectionBuilder(deploymentConfiguration,
                securityService, null)) {
            String clientId = Coerce.toString(deploymentConfiguration.getThingName()) + CLIENT_ID_SUFFIX;
            connection = builder.withClientId(clientId).build();
            int operationTimeoutMillis = MqttClient.getMqttOperationTimeoutMillis(deploymentConfiguration);
            connection.connect().get(operationTimeoutMillis, TimeUnit.MILLISECONDS);
            return true;
        } catch (MqttException e) {
            message = "Mqtt client failed to connect";
            cause = e;
        } catch (TimeoutException e) {
            message = "Mqtt client validation timed out";
            cause = e;
        } catch (ExecutionException e) {
            message = "Mqtt client validation completed exceptionally";
            cause = e;
        } catch (InterruptedException e) {
            message = "Mqtt client connection was interrupted";
            cause = e;
            Thread.currentThread().interrupt();
        } finally {
            if (connection != null) {
                connection.disconnect();
                connection.close();
            }
        }

        totallyCompleteFuture
                .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                        new ComponentConfigurationValidationException(message + WITH_NEW_CONFIG_MESSAGE_SUFFIX, cause,
                                DeploymentErrorCode.NUCLEUS_CONNECTIVITY_CONFIG_NOT_VALID)));
        return false;
    }

    /**
     * Creates an HTTP client and checks if the device can connect to AWS.
     *
     * @param totallyCompleteFuture Future will be updated if validation fails
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public boolean httpClientCanConnect(CompletableFuture<DeploymentResult> totallyCompleteFuture) {
        logger.atDebug().log("Checking HTTP client can connect");

        String message;
        Throwable cause;
        Pair<SdkHttpClient, GreengrassV2DataClient> pair = factory.createClientFromConfig(deploymentConfiguration);
        try (SdkHttpClient httpClient = pair.getLeft();
             GreengrassV2DataClient greengrassV2DataClient = pair.getRight()) {
            ListThingGroupsForCoreDeviceRequest request = ListThingGroupsForCoreDeviceRequest.builder()
                    .coreDeviceThingName(Coerce.toString(deploymentConfiguration.getThingName())).build();
            greengrassV2DataClient.listThingGroupsForCoreDevice(request);
            return true;
        } catch (Exception e) {
            message = "HTTP client validation failed" + WITH_NEW_CONFIG_MESSAGE_SUFFIX;
            cause = e;
        }

        totallyCompleteFuture
                .complete(new DeploymentResult(DeploymentResult.DeploymentStatus.FAILED_NO_STATE_CHANGE,
                        new ComponentConfigurationValidationException(message, cause,
                                DeploymentErrorCode.NUCLEUS_CONNECTIVITY_CONFIG_NOT_VALID)));
        return false;
    }

    /**
     * Checks if connectivity validation is enabled and device can connect to cloud.
     */
    public boolean isValidationEnabled() {
        boolean validationEnabled = deploymentConfiguration.isConnectivityValidationEnabled();
        boolean configuredToTalkToCloud = deploymentConfiguration.isDeviceConfiguredToTalkToCloud();
        if (validationEnabled && configuredToTalkToCloud) {
            return true;
        }
        logger.atDebug().log("Skipping connectivity validation");
        return false;
    }

    /**
     * Perform connectivity validation if configs have changed meaningfully.
     * Checks if validation is enabled
     * If MQTT related configurations have been changed validate MQTT connectivity
     * Then if HTTP related configurations have been changed validate HTTP connectivity
     *
     * @param totallyCompleteFuture      Future will be updated if validation fails
     * @param currentDeviceConfiguration current configs the device is using
     */
    @SuppressWarnings("PMD.SimplifyBooleanReturns")
    public boolean validate(CompletableFuture<DeploymentResult> totallyCompleteFuture,
                            DeviceConfiguration currentDeviceConfiguration) {
        boolean needsValidation;
        needsValidation = mqttClientNeedsValidation(currentDeviceConfiguration);
        if (needsValidation && !mqttClientCanConnect(totallyCompleteFuture)) {
            return false;
        }
        needsValidation = httpClientNeedsValidation(currentDeviceConfiguration);
        if (needsValidation && !httpClientCanConnect(totallyCompleteFuture)) {
            return false;
        }
        return true;
    }
}
