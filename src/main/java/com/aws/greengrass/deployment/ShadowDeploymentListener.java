/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import com.aws.greengrass.util.SerializerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotshadow.IotShadowClient;
import software.amazon.awssdk.iot.iotshadow.model.GetNamedShadowRequest;
import software.amazon.awssdk.iot.iotshadow.model.GetNamedShadowSubscriptionRequest;
import software.amazon.awssdk.iot.iotshadow.model.ShadowState;
import software.amazon.awssdk.iot.iotshadow.model.UpdateNamedShadowRequest;
import software.amazon.awssdk.iot.iotshadow.model.UpdateNamedShadowSubscriptionRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;

public class ShadowDeploymentListener implements InjectionActions {

    private static final long TIMEOUT_FOR_SUBSCRIBING_TO_TOPICS_SECONDS = Duration.ofMinutes(1).getSeconds();
    private static final long TIMEOUT_FOR_PUBLISHING_TO_TOPICS_SECONDS = Duration.ofMinutes(1).getSeconds();
    private static final long WAIT_TIME_TO_SUBSCRIBE_AGAIN_IN_MS = Duration.ofMinutes(2).toMillis();
    private static final Logger logger = LogManager.getLogger(ShadowDeploymentListener.class);
    public static final String CONFIGURATION_ARN_LOG_KEY_NAME = "CONFIGURATION_ARN";
    public static final String DEPLOYMENT_SHADOW_NAME = "AWSManagedGreengrassDeployment";
    //Keeps track of the deployment config-arn and the desired state, in the order in which deployments
    //were received.
    private final Queue<Pair<String, Map<String, Object>>> desiredStateQueue = new ConcurrentLinkedQueue<>();
    @Inject
    private DeploymentQueue deploymentQueue;
    @Inject
    private DeploymentStatusKeeper deploymentStatusKeeper;
    @Inject
    private MqttClient mqttClient;
    @Inject
    private ExecutorService executorService;
    @Inject
    private DeviceConfiguration deviceConfiguration;
    @Setter
    private IotShadowClient iotShadowClient;
    private String thingName;
    @Getter
    public MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            executorService.execute(() -> {
                // Get the shadow state when connection is re-established by publishing to get topic
                publishToGetDeviceShadowTopic();
                deploymentStatusKeeper.publishPersistedStatusUpdates(DeploymentType.SHADOW);
            });
        }
    };
    private String lastConfigurationArn;
    private Integer lastVersion;

    @Override
    public void postInject() {

        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            logger.atWarn().log("Device not configured to talk to AWS Iot cloud. Device will run in offline mode");
            return;
        }

        this.thingName = Coerce.toString(deviceConfiguration.getThingName());
        this.iotShadowClient = new IotShadowClient(getMqttClientConnection());
        mqttClient.addToCallbackEvents(callbacks);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.SHADOW,
                this::deploymentStatusChanged, ShadowDeploymentListener.class.getName());
        executorService.execute(() -> {
            subscribeToShadowTopics();
            // Get the shadow state when kernel starts up by publishing to get topic
            publishToGetDeviceShadowTopic();
        });
    }


    /*
        Subscribe to "$aws/things/{thingName}/shadow/update/accepted" topic to get notified when shadow is updated
        Subscribe to "$aws/things/{thingName}/shadow/get/accepted" topic to retrieve shadow by publishing to get topic
     */
    private void subscribeToShadowTopics() {
        while (true) {
            try {
                UpdateNamedShadowSubscriptionRequest updateNamedShadowSubscriptionRequest =
                        new UpdateNamedShadowSubscriptionRequest();
                updateNamedShadowSubscriptionRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
                updateNamedShadowSubscriptionRequest.thingName = thingName;
                iotShadowClient.SubscribeToUpdateNamedShadowAccepted(updateNamedShadowSubscriptionRequest,
                        QualityOfService.AT_LEAST_ONCE, updateShadowResponse ->
                                shadowUpdated(updateShadowResponse.state.desired, updateShadowResponse.version),
                        (e) -> logger.atError().log("Error processing updateShadowResponse", e))
                        .get(TIMEOUT_FOR_SUBSCRIBING_TO_TOPICS_SECONDS, TimeUnit.SECONDS);
                logger.info("Subscribed to update named shadow accepted topic");
                GetNamedShadowSubscriptionRequest getNamedShadowSubscriptionRequest
                        = new GetNamedShadowSubscriptionRequest();
                getNamedShadowSubscriptionRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
                getNamedShadowSubscriptionRequest.thingName = thingName;
                iotShadowClient.SubscribeToGetNamedShadowAccepted(getNamedShadowSubscriptionRequest,
                        QualityOfService.AT_MOST_ONCE,
                        getShadowResponse -> shadowUpdated(getShadowResponse.state.desired, getShadowResponse.version),
                        (e) -> logger.atError().log("Error processing getShadowResponse", e))
                        .get(TIMEOUT_FOR_SUBSCRIBING_TO_TOPICS_SECONDS, TimeUnit.SECONDS);
                logger.info("Subscribed to get named shadow topic");
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MqttException || cause instanceof TimeoutException) {
                    logger.atWarn().setCause(cause).log("Caught exception while subscribing to shadow topics, "
                            + "will retry shortly");
                    continue;
                }
                if (cause instanceof InterruptedException) {
                    logger.atWarn().log("Interrupted while subscribing to shadow topics");
                    return;
                }
                logger.atError().setCause(e).log("Caught exception while subscribing to shadow topics, "
                        + "will retry shortly");
            } catch (TimeoutException e) {
                logger.atWarn().setCause(e).log("Subscribe to shadow topics timed out, will retry shortly");
            } catch (InterruptedException e) {
                //Since this method can run as runnable cannot throw exception so handling exceptions here
                logger.atWarn().log("Interrupted while subscribing to shadow topics");
                return;
            }
            try {
                // Wait for sometime and then try to subscribe again
                Random jitter = new Random();
                Thread.sleep(WAIT_TIME_TO_SUBSCRIBE_AGAIN_IN_MS + jitter.nextInt(10_000));
            } catch (InterruptedException interruptedException) {
                logger.atWarn().log("Interrupted while subscribing to device shadow topics");
                return;
            }
        }
    }

    private void publishToGetDeviceShadowTopic() {
        GetNamedShadowRequest getNamedShadowRequest = new GetNamedShadowRequest();
        getNamedShadowRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
        getNamedShadowRequest.thingName = thingName;
        iotShadowClient.PublishGetNamedShadow(getNamedShadowRequest, QualityOfService.AT_LEAST_ONCE);
        logger.info("Published to get named shadow topic");
    }

    @SuppressFBWarnings
    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        DeploymentStatus status = DeploymentStatus.valueOf((String)
                deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME));

        String configurationArn = (String) deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME);
        // only update reported state when the deployment succeeds.
        if (DeploymentStatus.SUCCEEDED.equals(status)) {

            Pair<String, Map<String, Object>> desired = desiredStateQueue.peek();
            // discard configurations that might have got added to the queue but the deployment
            // got discarded before being processed due to a new shadow deployment
            while (desired != null && !desired.getLeft().equals(configurationArn)) {
                desiredStateQueue.poll();
                desired = desiredStateQueue.peek();
            }

            if (desired == null) {
                logger.atError().kv(CONFIGURATION_ARN_LOG_KEY_NAME, configurationArn)
                        .log("Unable to update shadow for deployment");
                return true;
            }

            try {
                ShadowState shadowState = new ShadowState();
                shadowState.reported = new HashMap<>(desired.getRight());
                UpdateNamedShadowRequest updateNamedShadowRequest = new UpdateNamedShadowRequest();
                updateNamedShadowRequest.shadowName = DEPLOYMENT_SHADOW_NAME;
                updateNamedShadowRequest.thingName = thingName;
                updateNamedShadowRequest.state = shadowState;
                iotShadowClient.PublishUpdateNamedShadow(updateNamedShadowRequest, QualityOfService.AT_LEAST_ONCE)
                        .get(TIMEOUT_FOR_PUBLISHING_TO_TOPICS_SECONDS, TimeUnit.SECONDS);
                desiredStateQueue.remove();
                logger.atInfo().kv(CONFIGURATION_ARN_LOG_KEY_NAME, configurationArn)
                        .log("Updated reported state for deployment");
                return true;
            } catch (InterruptedException e) {
                //Since this method can run as runnable cannot throw exception so handling exceptions here
                logger.atWarn().log("Interrupted while publishing reported state");
            } catch (ExecutionException e) {
                logger.atError().setCause(e).log("Caught exception while publishing reported state");
            } catch (TimeoutException e) {
                logger.atWarn().setCause(e).log("Publish reported state timed out, will retry shortly");
            }
            return false;
        }
        return true;
    }

    protected void shadowUpdated(Map<String, Object> configuration, Integer version) {
        if (configuration == null || configuration.isEmpty()) {
            logger.debug("Empty desired state, no device deployments created yet");
            return;
        }
        String configurationArn = (String) configuration.get("configurationArn");
        synchronized (ShadowDeploymentListener.class) {
            if (lastVersion != null && lastVersion > version) {
                logger.atInfo().kv(CONFIGURATION_ARN_LOG_KEY_NAME, configurationArn)
                        .kv("SHADOW_VERSION", version)
                        .log("Old deployment notification, Ignoring...");
                return;
            }
            if (lastConfigurationArn != null && lastConfigurationArn.equals(configurationArn)) {
                logger.atInfo().kv(CONFIGURATION_ARN_LOG_KEY_NAME, configurationArn)
                        .log("Duplicate deployment notification, Ignoring...");
                return;
            }
            lastConfigurationArn = configurationArn;
            lastVersion = version;
        }

        String configurationString;
        try {
            configurationString = SerializerFactory.getJsonObjectMapper().writeValueAsString(configuration);
        } catch (JsonProcessingException e) {
            logger.atError("Unable to process shadow update", e);
            return;
        }

        desiredStateQueue.add(new Pair<>(configurationArn, configuration));
        Deployment deployment =
                new Deployment(configurationString, DeploymentType.SHADOW, configurationArn);
        deploymentQueue.offer(deployment);
    }


    private MqttClientConnection getMqttClientConnection() {
        return new WrapperMqttClientConnection(mqttClient);
    }

}
