/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Utils;

import javax.inject.Inject;

/**
 * Manages persisted state for in-flight endpoint-switch deployments.
 *
 * <p>Before an endpoint switch, {@link DeploymentConfigMerger} persists the source IoT data endpoint and
 * deployment ID into DeploymentService's runtime config. After the switch completes, the status consumers
 * ({@link IotJobsHelper}, {@link ShadowDeploymentListener}) use this class to identify the endpoint-switch
 * deployment and report terminal status back to the source endpoint via a standalone MQTT connection.</p>
 *
 * <p>Keys are stored under {@code services.DeploymentService.runtime} and persisted to config.tlog
 * (survives crashes). Cleared after terminal status is reported.</p>
 */
public class EndpointSwitchState {

    static final String SOURCE_IOT_DATA_ENDPOINT_KEY = "sourceIotDataEndpoint";
    static final String SOURCE_DEPLOYMENT_ID_KEY = "sourceDeploymentId";
    static final String ENDPOINT_SWITCH_CLIENT_SUFFIX = "#endpoint-switch";
    static final long DEFAULT_STANDALONE_MQTT_TIMEOUT_MS = 60_000;
    static final String STANDALONE_MQTT_TIMEOUT_KEY = "standaloneMqttTimeoutMs";

    private final DeploymentService deploymentService;

    @Inject
    EndpointSwitchState(DeploymentService deploymentService) {
        this.deploymentService = deploymentService;
    }

    /**
     * Check if the given deployment is the in-flight endpoint-switch deployment by matching
     * the deployment ID against the persisted source deployment ID.
     *
     * @param deploymentId the deployment ID to check (job ID for IoT Jobs, config ARN for Shadow)
     * @return true if this is the endpoint-switch deployment
     */
    public boolean isEndpointSwitchDeployment(String deploymentId) {
        String sourceEndpoint = getSourceIotDataEndpoint();
        if (sourceEndpoint == null) {
            return false;
        }
        String sourceDepId = getSourceDeploymentId();
        return deploymentId != null && deploymentId.equals(sourceDepId);
    }

    /**
     * Read the IoT data endpoint where the endpoint-switch deployment originated.
     * This is the endpoint the device was connected to before the switch, used to report terminal
     * deployment status back to the source endpoint via a standalone MQTT connection.
     *
     * @return the source IoT data endpoint, or null if no endpoint switch is in progress
     */
    public String getSourceIotDataEndpoint() {
        String sourceEndpoint = Coerce.toString(getRuntimeConfig().find(SOURCE_IOT_DATA_ENDPOINT_KEY));
        if (Utils.isEmpty(sourceEndpoint)) {
            return null;
        }
        return sourceEndpoint;
    }

    /**
     * Persist the source endpoint and deployment ID before applying the endpoint switch.
     * Called by {@link DeploymentConfigMerger} after pre-flight passes.
     *
     * @param sourceEndpoint the current IoT data endpoint (before switch)
     * @param deploymentId   the deployment ID initiating the switch
     */
    public void persist(String sourceEndpoint, String deploymentId) {
        getRuntimeConfig().lookup(SOURCE_IOT_DATA_ENDPOINT_KEY).withValue(sourceEndpoint);
        getRuntimeConfig().lookup(SOURCE_DEPLOYMENT_ID_KEY).withValue(deploymentId);
    }

    /**
     * Remove the persisted source IoT data endpoint and deployment ID from runtime config.
     * Called after the endpoint-switch deployment's terminal status has been published to the
     * source endpoint (or publish failed — keys are cleared regardless to avoid stale state).
     */
    public void clear() {
        Topic sourceEndpointTopic = getRuntimeConfig().find(SOURCE_IOT_DATA_ENDPOINT_KEY);
        if (sourceEndpointTopic != null) {
            sourceEndpointTopic.remove();
        }
        Topic sourceDeploymentIdTopic = getRuntimeConfig().find(SOURCE_DEPLOYMENT_ID_KEY);
        if (sourceDeploymentIdTopic != null) {
            sourceDeploymentIdTopic.remove();
        }
    }

    private String getSourceDeploymentId() {
        return Coerce.toString(getRuntimeConfig().find(SOURCE_DEPLOYMENT_ID_KEY));
    }

    private Topics getRuntimeConfig() {
        return deploymentService.getRuntimeConfig();
    }
}
