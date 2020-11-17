/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.status;

import com.aws.greengrass.componentmanager.KernelConfigResolver;
import com.aws.greengrass.config.PlatformResolver;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.ImplementsService;
import com.aws.greengrass.dependency.State;
import com.aws.greengrass.deployment.DeploymentService;
import com.aws.greengrass.deployment.DeploymentStatusKeeper;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.testing.TestFeatureParameters;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.MqttChunkedPayloadPublisher;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.RandomUtils;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Inject;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeploymentService.COMPONENTS_TO_GROUPS_TOPICS;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_DETAILED_STATUS_KEY;
import static com.aws.greengrass.deployment.DeploymentService.DEPLOYMENT_FAILURE_CAUSE_KEY;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_TYPE_KEY_NAME;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.IOT_JOBS;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.LOCAL;
import static com.aws.greengrass.deployment.model.Deployment.DeploymentType.SHADOW;
import static com.aws.greengrass.lifecyclemanager.KernelVersion.KERNEL_VERSION;

@ImplementsService(name = FleetStatusService.FLEET_STATUS_SERVICE_TOPICS, autostart = true, version = "1.0.0")
public class FleetStatusService extends GreengrassService {
    public static final String FLEET_STATUS_SERVICE_TOPICS = "FleetStatusService";
    public static final String DEFAULT_FLEET_STATUS_SERVICE_PUBLISH_TOPIC =
            "$aws/things/{thingName}/greengrassv2/health/json";
    public static final String FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC = "fssPeriodicUpdateIntervalSec";
    public static final int DEFAULT_PERIODIC_UPDATE_INTERVAL_SEC = 86_400;
    static final String FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_SEC = "periodicUpdateIntervalSec";
    static final String FLEET_STATUS_SEQUENCE_NUMBER_TOPIC = "sequenceNumber";
    static final String FLEET_STATUS_LAST_PERIODIC_UPDATE_TIME_TOPIC = "lastPeriodicUpdateTime";
    private static final int MAX_PAYLOAD_LENGTH_BYTES = 128_000;

    private String updateTopic;
    private String thingName;
    private final MqttClient mqttClient;
    private final Kernel kernel;
    private final String architecture;
    private final String platform;
    private final MqttChunkedPayloadPublisher<ComponentStatusDetails> publisher;
    private final DeploymentStatusKeeper deploymentStatusKeeper;
    //For testing
    @Getter
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final AtomicBoolean isEventTriggeredUpdateInProgress = new AtomicBoolean(false);
    private final Set<GreengrassService> updatedGreengrassServiceSet =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ConcurrentHashMap<GreengrassService, Instant> allServiceNamesMap = new ConcurrentHashMap<>();
    private final AtomicBoolean isDeploymentInProgress = new AtomicBoolean(false);
    private final Object periodicUpdateInProgressLock = new Object();
    @Setter // Needed for integration tests.
    @Getter(AccessLevel.PACKAGE) // Needed for unit tests.
    private int periodicUpdateIntervalSec;
    private ScheduledFuture<?> periodicUpdateFuture;

    @Getter
    public MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            isConnected.set(false);
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            isConnected.set(true);
            schedulePeriodicFleetStatusDataUpdate(true);
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
                DEFAULT_PERIODIC_UPDATE_INTERVAL_SEC);
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
     * @param periodicUpdateIntervalSec     interval for cadence based status update.
     */
    public FleetStatusService(Topics topics, MqttClient mqttClient, DeploymentStatusKeeper deploymentStatusKeeper,
                              Kernel kernel, DeviceConfiguration deviceConfiguration,
                              PlatformResolver platformResolver, int periodicUpdateIntervalSec) {
        super(topics);

        this.mqttClient = mqttClient;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.kernel = kernel;
        this.publisher = new MqttChunkedPayloadPublisher<>(this.mqttClient);
        this.architecture = platformResolver.getCurrentPlatform()
                .getOrDefault(PlatformResolver.ARCHITECTURE_KEY, PlatformResolver.UNKNOWN_KEYWORD);
        this.periodicUpdateIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC, periodicUpdateIntervalSec).intValue();
        this.publisher.setMaxPayloadLengthBytes(MAX_PAYLOAD_LENGTH_BYTES);
        this.platform = platformResolver.getCurrentPlatform()
                .getOrDefault(PlatformResolver.OS_KEY, PlatformResolver.UNKNOWN_KEYWORD);

        updateThingNameAndPublishTopic(Coerce.toString(deviceConfiguration.getThingName()));
        deviceConfiguration.getThingName()
                .subscribe((why, node) -> updateThingNameAndPublishTopic(Coerce.toString(node)));

        topics.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_UPDATE_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    int newPeriodicUpdateIntervalSec = Coerce.toInt(newv);
                    // Do not update the scheduled interval if it is less than the default.
                    if (newPeriodicUpdateIntervalSec < DEFAULT_PERIODIC_UPDATE_INTERVAL_SEC) {
                        return;
                    }
                    this.periodicUpdateIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                            FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC, newPeriodicUpdateIntervalSec).intValue();
                    if (periodicUpdateFuture != null) {
                        schedulePeriodicFleetStatusDataUpdate(false);
                    }
                });

        topics.getContext().addGlobalStateChangeListener(this::handleServiceStateChange);

        this.deploymentStatusKeeper.registerDeploymentStatusConsumer(IOT_JOBS,
                this::deploymentStatusChanged, FLEET_STATUS_SERVICE_TOPICS);
        this.deploymentStatusKeeper.registerDeploymentStatusConsumer(LOCAL,
                this::deploymentStatusChanged, FLEET_STATUS_SERVICE_TOPICS);
        this.deploymentStatusKeeper.registerDeploymentStatusConsumer(SHADOW,
                this::deploymentStatusChanged, FLEET_STATUS_SERVICE_TOPICS);
        schedulePeriodicFleetStatusDataUpdate(false);

        this.mqttClient.addToCallbackEvents(callbacks);

        TestFeatureParameters.registerHandlerCallback(this.getName(), this::handleTestFeatureParametersHandlerChange);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleTestFeatureParametersHandlerChange(Boolean isDefault) {
        this.periodicUpdateIntervalSec = TestFeatureParameters.retrieveWithDefault(Double.class,
                FLEET_STATUS_TEST_PERIODIC_UPDATE_INTERVAL_SEC, this.periodicUpdateIntervalSec).intValue();
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
            if (lastPeriodicUpdateTime.plusSeconds(periodicUpdateIntervalSec).isBefore(Instant.now())) {
                updatePeriodicFleetStatusData();
            }
        }

        // Only trigger the event based updates on MQTT connection resumed. Else it will be triggered when the
        // service starts up as well, which is not needed.
        if (isDuringConnectionResumed) {
            updateEventTriggeredFleetStatusData(null);
        }

        // Add some jitter as an initial delay. If the fleet has a lot of devices associated to it,
        // we don't want all the devices to send the periodic update for fleet statuses at the same time.
        long initialDelay = RandomUtils.nextLong(0, periodicUpdateIntervalSec);
        ScheduledExecutorService ses = getContext().get(ScheduledExecutorService.class);
        this.periodicUpdateFuture = ses.scheduleWithFixedDelay(this::updatePeriodicFleetStatusData,
                initialDelay, periodicUpdateIntervalSec, TimeUnit.SECONDS);
    }

    @SuppressWarnings("PMD.UnusedFormalParameter")
    private void handleServiceStateChange(GreengrassService greengrassService, State oldState,
                                          State newState) {
        synchronized (updatedGreengrassServiceSet) {
            updatedGreengrassServiceSet.add(greengrassService);
        }

        // if there is no ongoing deployment and we encounter a BROKEN component, update the fleet status as UNHEALTHY.
        if (!isDeploymentInProgress.get() && newState.equals(State.BROKEN)) {
            uploadFleetStatusServiceData(updatedGreengrassServiceSet, OverallStatus.UNHEALTHY, null);
        }
    }

    private void updatePeriodicFleetStatusData() {
        // Do not update periodic updates if there is an ongoing deployment.
        if (isDeploymentInProgress.get()) {
            logger.atDebug().log("Not updating FSS data on a periodic basis since there is an ongoing deployment.");
            return;
        }
        if (!isConnected.get()) {
            logger.atDebug().log("Not updating FSS data on a periodic basis since MQTT connection is interrupted.");
            return;
        }
        logger.atDebug().log("Updating FSS data on a periodic basis.");
        synchronized (periodicUpdateInProgressLock) {
            updateFleetStatusUpdateForAllComponents();
            getPeriodicUpdateTimeTopic().withValue(Instant.now().toEpochMilli());
        }
    }

    /**
     * Update the Fleet Status information for all the components.
     */
    public void updateFleetStatusUpdateForAllComponents() {
        Set<GreengrassService> greengrassServiceSet = new HashSet<>();
        AtomicReference<OverallStatus> overAllStatus = new AtomicReference<>();

        // Get all running services from the kernel to update the fleet status.
        this.kernel.orderedDependencies().forEach(greengrassService -> {
            greengrassServiceSet.add(greengrassService);
            overAllStatus.set(getOverallStatusBasedOnServiceState(overAllStatus.get(), greengrassService));
        });
        uploadFleetStatusServiceData(greengrassServiceSet, overAllStatus.get(), null);
    }

    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        DeploymentType type = Coerce.toEnum(DeploymentType.class, deploymentDetails
                .get(DEPLOYMENT_TYPE_KEY_NAME));
        if (type == IOT_JOBS || type == SHADOW) {
            String status = deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME).toString();
            if (JobStatus.IN_PROGRESS.toString().equals(status)) {
                isDeploymentInProgress.set(true);
                return true;
            }
            logger.atDebug().log("Updating Fleet Status service for deployment with ID: {}",
                    deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME));
            isDeploymentInProgress.set(false);
            DeploymentInformation deploymentInformation = getDeploymentInformation(deploymentDetails);
            updateEventTriggeredFleetStatusData(deploymentInformation);
        }
        // TODO: [P41214799] Handle local deployment update for FSS
        return true;
    }



    private void updateEventTriggeredFleetStatusData(DeploymentInformation deploymentInformation) {
        if (!isConnected.get()) {
            logger.atDebug().log("Not updating FSS data on event triggered since MQTT connection is interrupted.");
            return;
        }

        // Return if we are already in the process of updating FSS data triggered by an event.
        if (!isEventTriggeredUpdateInProgress.compareAndSet(false, true)) {
            return;
        }

        Instant now = Instant.now();
        AtomicReference<OverallStatus> overAllStatus = new AtomicReference<>();

        // Check if the removed dependency is still running (Probably as a dependant service to another service).
        // If so, then remove it from the removedDependencies collection.
        this.kernel.orderedDependencies().forEach(greengrassService -> {
            allServiceNamesMap.put(greengrassService, now);
            overAllStatus.set(getOverallStatusBasedOnServiceState(overAllStatus.get(), greengrassService));
        });
        Set<GreengrassService> removedDependenciesSet = new HashSet<>();

        // Add all the removed dependencies to the collection of services to update.
        allServiceNamesMap.forEach((greengrassService, instant) -> {
            if (!instant.equals(now)) {
                updatedGreengrassServiceSet.add(greengrassService);
                removedDependenciesSet.add(greengrassService);
            }
        });
        removedDependenciesSet.forEach(allServiceNamesMap::remove);
        removedDependenciesSet.clear();
        uploadFleetStatusServiceData(updatedGreengrassServiceSet, overAllStatus.get(), deploymentInformation);
        isEventTriggeredUpdateInProgress.set(false);
    }

    private void uploadFleetStatusServiceData(Set<GreengrassService> greengrassServiceSet,
                                              OverallStatus overAllStatus,
                                              DeploymentInformation deploymentInformation) {
        if (!isConnected.get()) {
            logger.atDebug().log("Not updating fleet status data since MQTT connection is interrupted.");
            return;
        }
        List<ComponentStatusDetails> components = new ArrayList<>();
        long sequenceNumber;
        synchronized (greengrassServiceSet) {
            // If there are no Greengrass services to be updated, do not send an update.
            if (greengrassServiceSet.isEmpty()) {
                return;
            }

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
            greengrassServiceSet.forEach(service -> {
                if (isSystemLevelService(service)) {
                    return;
                }
                List<String> componentGroups = new ArrayList<>();
                if (finalComponentsToGroupsTopics != null) {
                    Topics groupsTopics = finalComponentsToGroupsTopics.lookupTopics(service.getName());
                    groupsTopics.children.values().stream().map(n -> (Topic) n).map(Topic::getName)
                            .forEach(groupName -> {
                                componentGroups.add(groupName);
                                // Get all the group names from the user components.
                                allGroups.add(groupName);
                            });
                }
                Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
                ComponentStatusDetails componentStatusDetails = ComponentStatusDetails.builder()
                        .componentName(service.getName())
                        .state(service.getState())
                        .version(Coerce.toString(versionTopic))
                        .fleetConfigArns(componentGroups)
                        .isRoot(finalDeploymentService.isComponentRoot(service.getName()))
                        .build();
                components.add(componentStatusDetails);
            });

            greengrassServiceSet.forEach(service -> {
                if (!isSystemLevelService(service)) {
                    return;
                }
                Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
                ComponentStatusDetails componentStatusDetails = ComponentStatusDetails.builder()
                        .componentName(service.getName())
                        .state(service.getState())
                        .version(Coerce.toString(versionTopic))
                        .fleetConfigArns(new ArrayList<>(allGroups))
                        .isRoot(false) // Set false for all system level services.
                        .build();
                components.add(componentStatusDetails);
            });
            greengrassServiceSet.clear();
            Topic sequenceNumberTopic = getSequenceNumberTopic();
            sequenceNumber = Coerce.toLong(sequenceNumberTopic);
            sequenceNumberTopic.withValue(sequenceNumber + 1);
        }

        FleetStatusDetails fleetStatusDetails = FleetStatusDetails.builder()
                .overallStatus(overAllStatus)
                .architecture(this.architecture)
                .platform(this.platform)
                .thing(thingName)
                .ggcVersion(KERNEL_VERSION)
                .sequenceNumber(sequenceNumber)
                .deploymentInformation(deploymentInformation)
                .build();
        publisher.publish(fleetStatusDetails, components);
        logger.atInfo().event("fss-status-update-published").log("Status update published to FSS");
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
                .fleetConfigurationArnForStatus((String) deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME)).build();
        if (deploymentDetails.containsKey(DEPLOYMENT_STATUS_DETAILS_KEY_NAME)) {
            Map<String, String> statusDetailsMap =
                    (Map<String, String>) deploymentDetails.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME);
            StatusDetails statusDetails = StatusDetails.builder()
                    .detailedStatus(statusDetailsMap.get(DEPLOYMENT_DETAILED_STATUS_KEY))
                    .failureCause(statusDetailsMap.get(DEPLOYMENT_FAILURE_CAUSE_KEY)).build();
            deploymentInformation.setStatusDetails(statusDetails);
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
        if (!this.periodicUpdateFuture.isCancelled()) {
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
        greengrassServices.forEach(greengrassService -> allServiceNamesMap.put(greengrassService, instant));
    }

    /**
     * Used for unit tests only.
     */
    void clearServiceSet() {
        updatedGreengrassServiceSet.clear();
    }

}
