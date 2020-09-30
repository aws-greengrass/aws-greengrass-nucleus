package com.aws.greengrass.deployment;

import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.FleetConfiguration;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.Pair;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Data;
import lombok.Getter;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotshadow.IotShadowClient;
import software.amazon.awssdk.iot.iotshadow.model.GetShadowRequest;
import software.amazon.awssdk.iot.iotshadow.model.GetShadowSubscriptionRequest;
import software.amazon.awssdk.iot.iotshadow.model.ShadowState;
import software.amazon.awssdk.iot.iotshadow.model.UpdateShadowRequest;
import software.amazon.awssdk.iot.iotshadow.model.UpdateShadowSubscriptionRequest;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.inject.Inject;
import javax.inject.Named;

import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENTS_QUEUE;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_DEVICE_DEPLOYMENT_ARN;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;

public class ShadowDeploymentListener implements InjectionActions {

    protected static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final long TIMEOUT_FOR_SUBSCRIBING_TO_TOPICS_SECONDS = Duration.ofMinutes(1).getSeconds();
    private static final long TIMEOUT_FOR_PUBLISHING_TO_TOPICS_SECONDS = Duration.ofMinutes(1).getSeconds();
    private static final long WAIT_TIME_TO_SUBSCRIBE_AGAIN_IN_MS = Duration.ofMinutes(2).toMillis();
    private static final Logger logger = LogManager.getLogger(ShadowDeploymentListener.class);
    @Inject
    @Named(DEPLOYMENTS_QUEUE)
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

    @Inject
    private DeploymentStatusKeeper deploymentStatusKeeper;

    @Inject
    private MqttClient mqttClient;

    @Inject
    private ExecutorService executorService;
    @Inject
    private DeviceConfiguration deviceConfiguration;
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
    //Once deployment succeeds, the reported state of the shadow needs to be synced with the desired state.
    //This map keeps track of configArn to desired state mapping. During rollback, the same configArn will be present
    // multiple deployments so a pair is used to keep track of the count of deployments with the same config arn.
    // The count is used for clean up purposes.
    private final Map<String, Pair<Integer, Map<String, Object>>> configArnToDesiredStateMap = new HashMap<>();
    private String lastConfigurationArn;
    private Integer lastVersion;

    @Override
    public void postInject() {

        try {
            deviceConfiguration.validate();
        } catch (DeviceConfigurationException e) {
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
                UpdateShadowSubscriptionRequest updateShadowSubscriptionRequest = new UpdateShadowSubscriptionRequest();
                updateShadowSubscriptionRequest.thingName = thingName;
                iotShadowClient.SubscribeToUpdateShadowAccepted(updateShadowSubscriptionRequest,
                        QualityOfService.AT_LEAST_ONCE, updateShadowResponse ->
                                shadowUpdated(updateShadowResponse.state.desired, updateShadowResponse.version),
                        (e) -> logger.atError().log("Error processing updateShadowResponse", e))
                        .get(TIMEOUT_FOR_SUBSCRIBING_TO_TOPICS_SECONDS, TimeUnit.SECONDS);
                GetShadowSubscriptionRequest request = new GetShadowSubscriptionRequest();
                request.thingName = thingName;
                logger.info("Subscribed to update device shadow topics" + thingName);
                iotShadowClient.SubscribeToGetShadowAccepted(request, QualityOfService.AT_MOST_ONCE,
                        getShadowResponse -> shadowUpdated(getShadowResponse.state.desired, getShadowResponse.version),
                        (e) -> logger.atError().log("Error processing getShadowResponse", e))
                        .get(TIMEOUT_FOR_SUBSCRIBING_TO_TOPICS_SECONDS, TimeUnit.SECONDS);
                logger.info("Subscribed to get device shadow topics" + thingName);
                return;
            } catch (ExecutionException e) {
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
        GetShadowRequest getShadowRequest = new GetShadowRequest();
        getShadowRequest.thingName = thingName;
        iotShadowClient.PublishGetShadow(getShadowRequest, QualityOfService.AT_LEAST_ONCE);
    }

    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        //TODO: publish status via FSS
        DeploymentStatus status = DeploymentStatus.valueOf((String)
                deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS));

        String configurationArn = (String)
                deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_DEVICE_DEPLOYMENT_ARN);
        Pair<Integer, Map<String, Object>> configurationCountPair = configArnToDesiredStateMap.get(configurationArn);
        // only update reported state when the deployment succeeds.
        if (DeploymentStatus.SUCCEEDED.equals(status)) {
            try {
                ShadowState shadowState = new ShadowState();
                shadowState.reported = new HashMap<>(configurationCountPair.getRight());

                UpdateShadowRequest updateShadowRequest = new UpdateShadowRequest();
                updateShadowRequest.thingName = thingName;
                updateShadowRequest.state = shadowState;
                iotShadowClient.PublishUpdateShadow(updateShadowRequest, QualityOfService.AT_LEAST_ONCE)
                        .get(TIMEOUT_FOR_PUBLISHING_TO_TOPICS_SECONDS, TimeUnit.SECONDS);
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
        if (DeploymentStatus.SUCCEEDED.equals(status) || DeploymentStatus.FAILED.equals(status)) {
            configurationCountPair.setLeft(configurationCountPair.getLeft() - 1);
            if (configurationCountPair.getLeft() == 0) {
                configArnToDesiredStateMap.remove(configurationArn);
            }
        }
        return true;
    }

    protected void shadowUpdated(Map<String, Object> configuration, Integer version) {
        if (configuration == null || configuration.isEmpty()) {
            logger.debug("Empty desired state, no device deployments created yet");
            return;
        }
        FleetConfiguration fleetConfiguration = OBJECT_MAPPER.convertValue(configuration, FleetConfiguration.class);
        synchronized (ShadowDeploymentListener.class) {
            if (lastVersion != null && lastVersion > version) {
                logger.atInfo().kv("CONFIGURATION_ARN", fleetConfiguration.getConfigurationArn())
                        .kv("SHADOW_VERSION", version)
                        .log("Old deployment notification, Ignoring...");
                return;
            }
            if (lastConfigurationArn != null && lastConfigurationArn.equals(fleetConfiguration.getConfigurationArn())) {
                logger.atInfo().kv("CONFIGURATION_ARN", fleetConfiguration.getConfigurationArn())
                        .log("Duplicate deployment notification, Ignoring...");
                return;
            }
            lastConfigurationArn = fleetConfiguration.getConfigurationArn();
            lastVersion = version;
        }
        configArnToDesiredStateMap.compute(fleetConfiguration.getConfigurationArn(), (arn, pair) -> {
            if (pair == null) {
                pair = new Pair(1, configuration);
            } else {
                pair.setLeft(pair.getLeft() + 1);
            }
            return pair;
        });
        Deployment deployment =
                new Deployment(fleetConfiguration, DeploymentType.SHADOW, fleetConfiguration.getConfigurationArn());
        deploymentsQueue.add(deployment);
    }


    private MqttClientConnection getMqttClientConnection() {
        return new WrapperMqttClientConnection(mqttClient);
    }

    @Data
    @SuppressWarnings("UWF_FIELD_NOT_INITIALIZED_IN_CONSTRUCTOR")
    @SuppressFBWarnings
    public static class DeviceDeploymentDetails {
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_DEVICE_DEPLOYMENT_ARN)
        private String configurationArn;
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS)
        private DeploymentStatus status;
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS)
        private Map<String, String> statusDetails;
        @JsonProperty(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE)
        private DeploymentType deploymentType;

        /**
         * Returns a map of string to object representing the deployment details.
         * @return Map of string to object
         */
        public Map<String, Object> convertToMapOfObjects() {
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_DEVICE_DEPLOYMENT_ARN, configurationArn);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, status.toString());
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS, statusDetails);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE, deploymentType.toString());
            return deploymentDetails;
        }
    }
}
