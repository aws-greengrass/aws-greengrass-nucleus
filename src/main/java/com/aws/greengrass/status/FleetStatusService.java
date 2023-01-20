/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status;

import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Subscriber;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.ThingGroupHelper;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import com.aws.greengrass.lifecyclemanager.GlobalStateChangeListener;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.SubscribeRequest;
import com.aws.greengrass.status.model.ComponentDetails;
import com.aws.greengrass.status.model.ComponentStatusDetails;
import com.aws.greengrass.status.model.DeploymentInformation;
import com.aws.greengrass.status.model.FleetStatusDetails;
import com.aws.greengrass.status.model.MessageType;
import com.aws.greengrass.status.model.OverallStatus;
import com.aws.greengrass.status.model.StatusDetails;
import com.aws.greengrass.status.model.Trigger;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.MqttChunkedPayloadPublisher;
import com.aws.greengrass.util.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.RandomUtils;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttMessage;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_STACK_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_ERROR_TYPES_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.CONFIGURATION_ARN_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ROOT_PACKAGES_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_TYPE_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.GG_DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.IOT_JOBS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.LOCAL;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.SHADOW;

@ImplementsService(name = FleetStatusService.FLEET_STATUS_SERVICE_TOPICS, autostart = true)
public class FleetStatusService extends GreengrassService {
    public static final String FLEET_STATUS_SERVICE_TOPICS = "FleetStatusService";
    public static final String DEFAULT_FLEET_STATUS_SERVICE_PUBLISH_TOPIC =
            "$aws/things/{thingName}/greengrassv2/health/json";
    public static final String FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC = "fssPeriodicUpdateIntervalSec";
    public static final int DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC = 86_400;
    public static final int MINIMAL_RECONNECT_PUBLISH_INTERVAL_SEC = 60;
    public static final String FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC = "periodicStatusPublishIntervalSeconds";
    static final String FLEET_STATUS_SEQUENCE_NUMBER_TOPIC = "sequenceNumber";
    static final String FLEET_STATUS_LAST_PERIODIC_UPDATE_TIME_TOPIC = "lastPeriodicUpdateTime";
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;
    public static final String DEVICE_OFFLINE_MESSAGE = "Device not configured to talk to AWS IoT cloud. "
            + "FleetStatusService is offline";

    // setter is only used for testing
    @Setter
    private DeviceConfiguration deviceConfiguration;
    private final GlobalStateChangeListener handleServiceStateChange = this::handleServiceStateChange;
    private final Function<Map<String, Object>, Boolean> deploymentStatusChanged = this::deploymentStatusChanged;

    private String updateTopic;
    private String thingName;
    private final MqttClient mqttClient;
    private final Kernel kernel;
    private final String architecture;
    private final String platform;
    private final MqttChunkedPayloadPublisher<ComponentDetails> publisher;
    private final DeploymentStatusKeeper deploymentStatusKeeper;
    //For testing
    @Getter
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final AtomicBoolean isFSSSetupComplete = new AtomicBoolean(false);
    private final Set<GreengrassService> updatedGreengrassServiceSet =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<GreengrassService, Instant> serviceFssTracksMap = new ConcurrentHashMap<>();
    private final AtomicBoolean isDeploymentInProgress = new AtomicBoolean(false);
    private final Object periodicUpdateInProgressLock = new Object();
    @Setter // Needed for integration tests.
    @Getter(AccessLevel.PACKAGE) // Needed for unit tests.
    private int periodicPublishIntervalSec;

    // where the status payload will send to
    // equal to whatever in the nucleus config
    private String outgoingMqttTopic;

    @Inject
    private ThingGroupHelper thingGroupHelper;

    private ScheduledFuture<?> periodicUpdateFuture;
    // default to zero so that first Reconnect update would go thru
    private Instant lastReconnectUpdateTime = Instant.EPOCH;

    @Getter
    public MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            isConnected.set(false);
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            isConnected.set(true);
            subscribeToMqttFss();
            schedulePeriodicFleetStatusDataUpdate(true);
        }
    };
    private final Subscriber publishIntervalSubscriber = (why, newv) -> {
        int newPeriodicUpdateIntervalSec = Coerce.toInt(newv);
        // Do not update the scheduled interval if it is less than the default.
        if (newPeriodicUpdateIntervalSec < DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC) {
            return;
        }
        this.periodicPublishIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC, newPeriodicUpdateIntervalSec).intValue();
        if (periodicUpdateFuture != null) {
            schedulePeriodicFleetStatusDataUpdate(false);
        }
    };

    /**
     * Constructor for FleetStatusService.
     *
     * @param topics                 root Configuration topic for this service
     * @param mqttClient             {@link MqttClient}
     * @param deploymentStatusKeeper {@link DeploymentStatusKeeper}
     * @param kernel                 {@link Kernel}
     * @param deviceConfiguration    {@link DeviceConfiguration}
     * @param platformResolver       {@link PlatformResolver}
     */
    @Inject
    public FleetStatusService(Topics topics, MqttClient mqttClient, DeploymentStatusKeeper deploymentStatusKeeper,
                              Kernel kernel, DeviceConfiguration deviceConfiguration,
                              PlatformResolver platformResolver) {
        this(topics, mqttClient, deploymentStatusKeeper, kernel, deviceConfiguration, platformResolver,
                DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC);
    }

    /**
     * Constructor for FleetStatusService.
     *
     * @param topics                        root Configuration topic for this service
     * @param mqttClient                    {@link MqttClient}
     * @param deploymentStatusKeeper        {@link DeploymentStatusKeeper}
     * @param kernel                        {@link Kernel}
     * @param deviceConfiguration           {@link DeviceConfiguration}
     * @param platformResolver              {@link PlatformResolver}
     * @param periodicPublishIntervalSec     interval for cadence based status update.
     */
    public FleetStatusService(Topics topics, MqttClient mqttClient, DeploymentStatusKeeper deploymentStatusKeeper,
                              Kernel kernel, DeviceConfiguration deviceConfiguration,
                              PlatformResolver platformResolver, int periodicPublishIntervalSec) {
        super(topics);
        this.mqttClient = mqttClient;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.kernel = kernel;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.architecture = platformResolver.getCurrentPlatform()
                .getOrDefault(PlatformResolver.ARCHITECTURE_KEY, PlatformResolver.UNKNOWN_KEYWORD);
        this.periodicPublishIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC, periodicPublishIntervalSec).intValue();
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        this.platform = platformResolver.getCurrentPlatform()
                .getOrDefault(PlatformResolver.OS_KEY, PlatformResolver.UNKNOWN_KEYWORD);

        updateThingNameAndPublishTopic(Coerce.toString(deviceConfiguration.getThingName()));
        deviceConfiguration.getThingName()
                .subscribe((why, node) -> updateThingNameAndPublishTopic(Coerce.toString(node)));
        this.deviceConfiguration = deviceConfiguration;
        this.mqttClient.addToCallbackEvents(callbacks);
        this.subscribeToMqttFss();
        TestFeatureParameters.registerHandlerCallback(this.getName(), this::handleTestFeatureParametersHandlerChange);

        //populating services when kernel starts up
        Instant now = Instant.now();
        this.kernel.orderedDependencies().forEach(greengrassService -> {
            serviceFssTracksMap.put(greengrassService, now);
        });
    }

    private void subscribeToMqttFss() {
        logger.atInfo().log("testFssMqtt - subscribing to mqtt fss");
        Optional<Set<String>> groupsForDeviceOpt = Optional.empty();
        try {
            groupsForDeviceOpt = this.thingGroupHelper.listThingGroupsForDevice(3);
        } catch (Exception e) {
            logger.atError().log("testFssMqtt - Failed to list thing groups. Will proceed with default");
        }
        Set<String> groupsForDevice = groupsForDeviceOpt.orElse(Collections.emptySet());
        logger.atInfo().kv("groups", groupsForDevice).log("testFssMqtt - device thing group membership");

        List<String> incomingTopics = new ArrayList<>();
        groupsForDevice.forEach(group -> incomingTopics.add(String.format("status/%s/%s", group, thingName)));
        incomingTopics.add(String.format("status/%s", thingName));
        Consumer<MqttMessage> handler = (message) -> {
            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
            try {
                if (!payload.isEmpty()) {
                    logger.atInfo().log();
                    updateFleetStatusUpdateForAllComponents(Trigger.CADENCE);
                }
            } catch (Exception e) {
                logger.atError().kv("payload", payload).setCause(e)
                        .log("testFssMqtt - failed to handle mqtt payload");
            }
        };
        for (String topic : incomingTopics) {
            SubscribeRequest subscribeRequest = SubscribeRequest.builder().callback(handler)
                    .topic(topic).qos(QualityOfService.AT_LEAST_ONCE).build();
            try {
                this.mqttClient.subscribe(subscribeRequest);
                logger.atInfo().kv("topic", topic).log("testFssMqtt - successfully subscribed to incoming topic");
            } catch (Exception e) {
                logger.atError().setCause(e).kv("subscribeRequest", subscribeRequest)
                        .log("testFssMqtt - fail to subscribe mqtt");
            }
        }
    }

    @Override
    public void postInject() {
        super.postInject();

        deviceConfiguration.onAnyChange((what, node) -> {
            if (node != null && WhatHappened.childChanged.equals(what)
                    && DeviceConfiguration.provisionInfoNodeChanged(node, false)) {
                try {
                    setUpFSS();
                } catch (DeviceConfigurationException e) {
                    logger.atWarn().kv("errorMessage", e.getMessage()).log(DEVICE_OFFLINE_MESSAGE);
                }
            }
        });
        try {
            setUpFSS();
        } catch (DeviceConfigurationException e) {
            logger.atWarn().kv("errorMessage", e.getMessage()).log(DEVICE_OFFLINE_MESSAGE);
        }
    }

    private void setUpFSS() throws DeviceConfigurationException {
        // Not using isDeviceConfiguredToTalkToCloud() in order to provide the detailed error message to user
        deviceConfiguration.validate();
        if (isFSSSetupComplete.compareAndSet(false, true)) {
            Topics configurationTopics = deviceConfiguration.getStatusConfigurationTopics();
            configurationTopics.lookup(FLEET_STATUS_PERIODIC_PUBLISH_INTERVAL_SEC)
                    .dflt(DEFAULT_PERIODIC_PUBLISH_INTERVAL_SEC).subscribe(publishIntervalSubscriber);

            configurationTopics.lookup("outgoing")
                    .dflt("defaultTopic").subscribe(outgoingTopicSubscriber);

            config.getContext().addGlobalStateChangeListener(handleServiceStateChange);

            this.deploymentStatusKeeper.registerDeploymentStatusConsumer(IOT_JOBS, deploymentStatusChanged,
                    FLEET_STATUS_SERVICE_TOPICS);
            this.deploymentStatusKeeper.registerDeploymentStatusConsumer(LOCAL, deploymentStatusChanged,
                    FLEET_STATUS_SERVICE_TOPICS);
            this.deploymentStatusKeeper.registerDeploymentStatusConsumer(SHADOW, deploymentStatusChanged,
                    FLEET_STATUS_SERVICE_TOPICS);
            schedulePeriodicFleetStatusDataUpdate(false);
        }
    }

    // when nucleus config change, read the config from status/outgoing and assign the value to this.outgoingMqttTopic
    private final Subscriber outgoingTopicSubscriber = (why, newv) -> {
        String newTopic = Coerce.toString(newv);
        if (!Utils.isEmpty(newTopic)) {
            this.outgoingMqttTopic = newTopic;
            logger.atInfo().kv("newv", newTopic).log("testFssMqtt - changing outgoingMqttTopic");
        }
    };

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleTestFeatureParametersHandlerChange(Boolean isDefault) {
        this.periodicPublishIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC, this.periodicPublishIntervalSec).intValue();
        if (periodicUpdateFuture != null) {
            schedulePeriodicFleetStatusDataUpdate(false);
        }
    }

    private void updateThingNameAndPublishTopic(String newThingName) {
        if (newThingName != null) {
            thingName = newThingName;
            updateTopic = DEFAULT_FLEET_STATUS_SERVICE_PUBLISH_TOPIC.replace("{thingName}", thingName);
            this.publisher.setUpdateTopic(updateTopic);
        }
    }

    /**
     * Schedule cadence based periodic updates for fleet status.
     *
     * @param isDuringConnectionResumed boolean to indicate if the cadence based update is being rescheduled after
     *                                  connection resumed.
     */
    public void schedulePeriodicFleetStatusDataUpdate(boolean isDuringConnectionResumed) {
        // If the last periodic update was missed, update the fleet status service for all running services.
        // Else update only the statuses of the services whose status changed (if any) and if the method is called
        // due to a MQTT connection resumption.
        if (periodicUpdateFuture != null) {
            periodicUpdateFuture.cancel(false);
        }

        synchronized (periodicUpdateInProgressLock) {
            Instant lastPeriodicUpdateTime = Instant.ofEpochMilli(Coerce.toLong(getPeriodicUpdateTimeTopic()));
            if (lastPeriodicUpdateTime.plusSeconds(periodicPublishIntervalSec).isBefore(Instant.now())) {
                updatePeriodicFleetStatusData();
            }
        }

        // Only trigger the event based updates on MQTT connection resumed. Else it will be triggered when the
        // service starts up as well, which is not needed.
        if (isDuringConnectionResumed) {
            updateEventTriggeredFleetStatusData(null, Trigger.RECONNECT);
        }

        // Add some jitter as an initial delay. If the fleet has a lot of devices associated to it,
        // we don't want all the devices to send the periodic update for fleet statuses at the same time.
        long initialDelay = RandomUtils.nextLong(0, periodicPublishIntervalSec);
        ScheduledExecutorService ses = getContext().get(ScheduledExecutorService.class);
        this.periodicUpdateFuture = ses.scheduleWithFixedDelay(this::updatePeriodicFleetStatusData,
                initialDelay, periodicPublishIntervalSec, TimeUnit.SECONDS);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleServiceStateChange(GreengrassService greengrassService, State oldState,
                                          State newState) {
        // Not reporting status of other components in errored component message because a deployment could
        // be in-progress and other component's fleet config arn may not have updated
        if (newState.equals(State.ERRORED)) {
            Set<GreengrassService> erroredComponentSet = new HashSet<>();
            erroredComponentSet.add(greengrassService);

            // Evaluate overall status based on current state of all components
            Instant now = Instant.now();
            AtomicReference<OverallStatus> overAllStatus = new AtomicReference<>(OverallStatus.HEALTHY);
            this.kernel.orderedDependencies().forEach(service -> {
                serviceFssTracksMap.put(service, now);
                overAllStatus.set(getOverallStatusBasedOnServiceState(overAllStatus.get(), service));
            });

            uploadFleetStatusServiceData(erroredComponentSet, overAllStatus.get(), null, Trigger.ERRORED_COMPONENT);
            return;
        }

        synchronized (updatedGreengrassServiceSet) {
            updatedGreengrassServiceSet.add(greengrassService);
        }

        // if there is no ongoing deployment and we encounter a BROKEN component, update the fleet status as UNHEALTHY.
        if (!isDeploymentInProgress.get() && newState.equals(State.BROKEN)) {
            synchronized (updatedGreengrassServiceSet) {
                uploadFleetStatusServiceData(updatedGreengrassServiceSet, OverallStatus.UNHEALTHY,
                        null, Trigger.BROKEN_COMPONENT);
            }
        }
    }

    private void updatePeriodicFleetStatusData() {
        // Do not update periodic updates if there is an ongoing deployment.
        if (isDeploymentInProgress.get()) {
            logger.atDebug().log("Not updating FSS data on a periodic basis since there is an ongoing deployment");
            return;
        }
        if (!isConnected.get()) {
            logger.atDebug().log("Not updating FSS data on a periodic basis since MQTT connection is interrupted");
            return;
        }
        logger.atDebug().log("Updating FSS data on a periodic basis");
        synchronized (periodicUpdateInProgressLock) {
            updateFleetStatusUpdateForAllComponents(Trigger.CADENCE);
            getPeriodicUpdateTimeTopic().withValue(Instant.now().toEpochMilli());
        }
    }

    /**
     * Trigger a Fleet Status update at kernel launch.
     */
    public void triggerFleetStatusUpdateAtKernelLaunch() {
        if (!deviceConfiguration.isDeviceConfiguredToTalkToCloud()) {
            logger.atWarn().kv("trigger", Trigger.NUCLEUS_LAUNCH).log("Status won't be published until Nucleus is "
                    + "configured online");
            return;
        }
        updateFleetStatusUpdateForAllComponents(Trigger.NUCLEUS_LAUNCH);
    }

    /**
     * Update the Fleet Status information for all the components.
     * This function calls under assumption that device is configured to talk to cloud.
     * @param trigger Trigger of FSS update
     */
    public void updateFleetStatusUpdateForAllComponents(Trigger trigger) {
        Set<GreengrassService> greengrassServiceSet = new HashSet<>();
        AtomicReference<OverallStatus> overAllStatus = new AtomicReference<>(OverallStatus.HEALTHY);

        // Get all running services from the Nucleus to update the fleet status.
        this.kernel.orderedDependencies().forEach(greengrassService -> {
            greengrassServiceSet.add(greengrassService);
            overAllStatus.set(getOverallStatusBasedOnServiceState(overAllStatus.get(), greengrassService));
        });
        uploadFleetStatusServiceData(greengrassServiceSet, overAllStatus.get(), null, trigger);
    }

    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        String status = deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME).toString();
        if (JobStatus.IN_PROGRESS.toString().equals(status)) {
            isDeploymentInProgress.set(true);
            return true;
        }

        logger.atDebug().kv("deployment details", deploymentDetails)
                .log("Updating Fleet Status service for deployment");
        isDeploymentInProgress.set(false);
        DeploymentInformation deploymentInformation = getDeploymentInformation(deploymentDetails);
        try {
            updateEventTriggeredFleetStatusData(deploymentInformation, Trigger.fromDeploymentType(
                    Coerce.toEnum(DeploymentType.class, deploymentDetails.get(DEPLOYMENT_TYPE_KEY_NAME))));
        } catch (IllegalArgumentException e) {
            logger.atWarn().setCause(e).log("Skipping FSS data update due to invalid deployment type");
        }

        return true;
    }

    private void updateEventTriggeredFleetStatusData(DeploymentInformation deploymentInformation,
                                                     Trigger trigger) {
        if (!isConnected.get()) {
            // spool deployment updates even if mqtt connection interrupted
            if (Trigger.isCloudDeploymentTrigger(trigger)) {
                logger.atDebug().log("Attempting to publish and spool cloud deployment FSS updates even though MQTT "
                        + "connection is interrupted");
            } else {
                logger.atDebug().log("Not updating FSS data on local deployment and component events since MQTT "
                        + "connection is interrupted");
                return;
            }
        }

        Instant now = Instant.now();
        AtomicReference<OverallStatus> overAllStatus = new AtomicReference<>(OverallStatus.HEALTHY);

        // if last event-triggered update is still ongoing, wait for it to finish
        synchronized (updatedGreengrassServiceSet) {
            // Check if the removed dependency is still running (Probably as a dependant service to another service).
            // If so, then remove it from the removedDependencies collection.
            this.kernel.orderedDependencies().forEach(greengrassService -> {
                serviceFssTracksMap.put(greengrassService, now);
                overAllStatus.set(getOverallStatusBasedOnServiceState(overAllStatus.get(), greengrassService));
            });
            Set<GreengrassService> removedDependenciesSet = new HashSet<>();

            // Add all the removed dependencies to the collection of services to update.
            serviceFssTracksMap.forEach((greengrassService, instant) -> {
                if (!instant.equals(now)) {
                    updatedGreengrassServiceSet.add(greengrassService);
                    removedDependenciesSet.add(greengrassService);
                }
            });
            removedDependenciesSet.forEach(serviceFssTracksMap::remove);
            removedDependenciesSet.clear();

            // Drop empty FSS reconnect messages if last reconnect message is sent within 60 seconds
            // to avoid spamming FSS messages in case of flaky network
            // TODO: better throttling mechanism for FSS updates
            if (updatedGreengrassServiceSet.isEmpty() && Trigger.RECONNECT.equals(trigger)
                    && lastReconnectUpdateTime.plusSeconds(MINIMAL_RECONNECT_PUBLISH_INTERVAL_SEC)
                        .isAfter(Instant.now())) {
                return;
            }

            // remove any component from unchanged status component list if it's in updatedGreengrassServiceSet
            if (deploymentInformation != null && deploymentInformation.getUnchangedRootComponents() != null) {
                deploymentInformation.getUnchangedRootComponents().removeIf(
                        componentName -> updatedGreengrassServiceSet.stream()
                                .anyMatch(service -> service.getName().equals(componentName)));
            }
            uploadFleetStatusServiceData(updatedGreengrassServiceSet, overAllStatus.get(), deploymentInformation,
                    trigger);

            // Update the timestamp of last reconnect update
            if (Trigger.RECONNECT.equals(trigger)) {
                lastReconnectUpdateTime = Instant.now();
            }
        }
    }

    private void uploadFleetStatusServiceData(Set<GreengrassService> greengrassServiceSet,
                                              OverallStatus overAllStatus,
                                              DeploymentInformation deploymentInformation,
                                              Trigger trigger) {
        if (!isConnected.get() && !Trigger.isCloudDeploymentTrigger(trigger)) {
            logger.atDebug().log("Not updating fleet status data since MQTT connection is interrupted");
            return;
        }
        List<ComponentDetails> components = new ArrayList<>();

        //When a component version is bumped up, FSS may have pointers to both old and new service instances
        //Filtering out the old version and only sending the update for the new version
        Set<GreengrassService> filteredServices = new HashSet<>();
        greengrassServiceSet.forEach(service -> {
            try {
                GreengrassService runningService = kernel.locate(service.getName());
                filteredServices.add(runningService);
            } catch (ServiceLoadException e) {
                //not able to find service, service might be removed.
                filteredServices.add(service);
            }
        });

        Topics componentsToGroupsTopics = null;
        HashSet<String> allGroups = new HashSet<>();
        DeploymentService deploymentService = null;
        try {
            GreengrassService deploymentServiceLocateResult = this.kernel
                    .locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
            if (deploymentServiceLocateResult instanceof DeploymentService) {
                deploymentService = (DeploymentService) deploymentServiceLocateResult;
                componentsToGroupsTopics = deploymentService.getConfig().lookupTopics(COMPONENTS_TO_GROUPS_TOPICS);
            }
        } catch (ServiceLoadException e) {
            logger.atError().cause(e).log("Unable to locate {} service while uploading FSS data",
                    DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
        }

        Topics finalComponentsToGroupsTopics = componentsToGroupsTopics;

        DeploymentService finalDeploymentService = deploymentService;
        filteredServices.forEach(service -> {
            if (isSystemLevelService(service)) {
                return;
            }
            List<String> componentGroups = new ArrayList<>();
            if (finalComponentsToGroupsTopics != null) {
                Topics groupsTopics = finalComponentsToGroupsTopics.findTopics(service.getName());
                if (groupsTopics != null) {
                    groupsTopics.children.values().stream().map(n -> (Topic) n).map(Topic::getName)
                            .forEach(groupName -> {
                                componentGroups.add(groupName);
                                // Get all the group names from the user components.
                                allGroups.add(groupName);
                            });
                }
            }
            Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
            ComponentDetails componentDetails = ComponentDetails.builder()
                    .componentName(service.getName())
                    .state(service.getState())
                    .componentStatusDetails(getComponentStatusDetails(service))
                    .version(Coerce.toString(versionTopic))
                    .fleetConfigArns(componentGroups)
                    .isRoot(finalDeploymentService.isComponentRoot(service.getName()))
                    .build();
            components.add(componentDetails);
        });

        filteredServices.forEach(service -> {
            if (!isSystemLevelService(service)) {
                return;
            }
            Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
            ComponentDetails componentDetails = ComponentDetails.builder()
                    .componentName(service.getName())
                    .state(service.getState())
                    .componentStatusDetails(getComponentStatusDetails(service))
                    .version(Coerce.toString(versionTopic))
                    .fleetConfigArns(new ArrayList<>(allGroups))
                    .isRoot(false) // Set false for all system level services.
                    .build();
            components.add(componentDetails);
        });
        greengrassServiceSet.clear();
        Topic sequenceNumberTopic = getSequenceNumberTopic();
        long sequenceNumber = Coerce.toLong(sequenceNumberTopic);
        sequenceNumberTopic.withValue(sequenceNumber + 1);

        FleetStatusDetails fleetStatusDetails = FleetStatusDetails.builder()
                .overallStatus(overAllStatus)
                .architecture(this.architecture)
                .platform(this.platform)
                .thing(thingName)
                .ggcVersion(deviceConfiguration.getNucleusVersion())
                .sequenceNumber(sequenceNumber)
                .timestamp(Instant.now().toEpochMilli())
                .trigger(trigger)
                .messageType(MessageType.fromTrigger(trigger))
                .deploymentInformation(deploymentInformation)
                .build();

        publisher.publish(fleetStatusDetails, components);
        logger.atInfo().event("fss-status-update-published").kv("trigger", trigger)
                .log("Status update published to FSS");
        if (!Utils.isEmpty(this.outgoingMqttTopic)) {
            logger.atInfo().kv("outgoingMqttTopic", outgoingMqttTopic).log("testMqttFss - publishing to outgoingMqttTopic");
            publisher.setUpdateTopic(outgoingMqttTopic);
            publisher.publish(fleetStatusDetails, components);
            publisher.setUpdateTopic(updateTopic);
        }
    }

    /* Only set status details in FSS message if component is ERRORED or BROKEN */
    private ComponentStatusDetails getComponentStatusDetails(GreengrassService service) {
        if (service.inState(State.BROKEN) || service.inState(State.ERRORED)) {
            return service.getStatusDetails();
        }
        return null;
    }

    private Topic getSequenceNumberTopic() {
        return config.lookup(FLEET_STATUS_SEQUENCE_NUMBER_TOPIC);
    }

    private Topic getPeriodicUpdateTimeTopic() {
        return config.lookup(FLEET_STATUS_LAST_PERIODIC_UPDATE_TIME_TOPIC).dflt(Instant.now().toEpochMilli());
    }

    private boolean isSystemLevelService(GreengrassService service) {
        return service.isBuiltin() || service.getName().equals("main");
    }

    private OverallStatus getOverallStatusBasedOnServiceState(OverallStatus overallStatus,
                                                              GreengrassService greengrassService) {
        if (State.BROKEN.equals(greengrassService.getState())
                || OverallStatus.UNHEALTHY.equals(overallStatus)) {
            return OverallStatus.UNHEALTHY;
        }
        return OverallStatus.HEALTHY;
    }

    private DeploymentInformation getDeploymentInformation(Map<String, Object> deploymentDetails) {
        DeploymentInformation deploymentInformation = DeploymentInformation.builder()
                .status((String) deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME))
                // Reporting GG deployment id in FSS because ListInstalledComponents API
                // relies on this field to set up last installation source link to deployments.
                .deploymentId((String) deploymentDetails.get(GG_DEPLOYMENT_ID_KEY_NAME))
                .fleetConfigurationArnForStatus((String) deploymentDetails.get(CONFIGURATION_ARN_KEY_NAME)).build();
        if (deploymentDetails.containsKey(DEPLOYMENT_STATUS_DETAILS_KEY_NAME)) {
            Map<String, Object> statusDetailsMap =
                    (Map<String, Object>) deploymentDetails.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME);
            StatusDetails statusDetails = StatusDetails.builder()
                    .detailedStatus((String) statusDetailsMap.get(DEPLOYMENT_DETAILED_STATUS_KEY))
                    .failureCause((String) statusDetailsMap.get(DEPLOYMENT_FAILURE_CAUSE_KEY))
                    .errorStack((List<String>) statusDetailsMap.get(DEPLOYMENT_ERROR_STACK_KEY))
                    .errorTypes((List<String>) statusDetailsMap.get(DEPLOYMENT_ERROR_TYPES_KEY))
                    .build();
            deploymentInformation.setStatusDetails(statusDetails);
        }
        // Use unchangedRootComponents to update lastInstallationSource and lastReportedTimestamp in cloud.
        if (deploymentDetails.containsKey(DEPLOYMENT_ROOT_PACKAGES_KEY_NAME)) {
            // Setting the unchangedRootComponents to be the entire list of root packages, and then later
            // if a component changed state since last FSS update we will remove it from this list.
            deploymentInformation.setUnchangedRootComponents((List<String>) deploymentDetails
                    .get(DEPLOYMENT_ROOT_PACKAGES_KEY_NAME));
        }
        return deploymentInformation;
    }

    @Override
    @SuppressWarnings("PMD.UselessOverridingMethod")
    public void startup() throws InterruptedException {
        // Need to override the function for tests.
        super.startup();
    }

    @Override
    public void shutdown() {
        if (this.periodicUpdateFuture != null && !this.periodicUpdateFuture.isCancelled()) {
            this.periodicUpdateFuture.cancel(true);
        }
        TestFeatureParameters.unRegisterHandlerCallback(this.getName());
    }

    /**
     * Used for unit tests only. Adds a list of Greengrass services of previously
     *
     * @param greengrassServices List of Greengrass services to add
     * @param instant           last time the service was processed.
     */
    void addServicesToPreviouslyKnownServicesList(List<GreengrassService> greengrassServices,
                                                           Instant instant) {
        greengrassServices.forEach(greengrassService -> serviceFssTracksMap.put(greengrassService, instant));
    }

    /**
     * Used for unit and integration tests only.
     */
    public void clearServiceSet() {
        updatedGreengrassServiceSet.clear();
    }
}
