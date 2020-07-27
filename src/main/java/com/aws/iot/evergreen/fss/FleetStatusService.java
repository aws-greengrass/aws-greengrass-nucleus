/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.fss;

import com.amazonaws.arn.Arn;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.DeploymentService;
import com.aws.iot.evergreen.deployment.DeploymentStatusKeeper;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.kernel.exceptions.ServiceLoadException;
import com.aws.iot.evergreen.mqtt.MqttClient;
import com.aws.iot.evergreen.mqtt.PublishRequest;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY;
import static com.aws.iot.evergreen.deployment.DeploymentService.GROUP_TO_ROOT_COMPONENTS_TOPICS;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID;
import static com.aws.iot.evergreen.deployment.DeploymentStatusKeeper.PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

@ImplementsService(name = FleetStatusService.FLEET_STATUS_SERVICE_TOPICS, autostart = true, version = "1.0.0")
public class FleetStatusService extends EvergreenService {
    public static final String FLEET_STATUS_SERVICE_TOPICS = "FleetStatusService";
    public static final String FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS = "periodicUpdateIntervalMs";
    public static final String FLEET_STATUS_ARN_SERVICE = "greengrass";
    public static final String FLEET_STATUS_ARN_PARTITION = "aws";
    public static final String FLEET_STATUS_ARN_RESOURCE_PREFIX = "configuration:%s:%s";
    public static final String FLEET_STATUS_SERVICE_PUBLISH_TOPIC = "$aws/things/{thingName}/evergreen/health/json";
    private static AtomicLong sequenceNumber = new AtomicLong();
    private static final ObjectMapper SERIALIZER = new ObjectMapper();
    private static final int DEFAULT_PERIODIC_UPDATE_INTERVAL_MS = 86_400_000;

    private final String kernelVersion;
    private int periodicUpdateIntervalMs;
    private final MqttClient mqttClient;
    private final DeviceConfiguration deviceConfiguration;
    private final Kernel kernel;
    private final DeploymentStatusKeeper deploymentStatusKeeper;
    private final Map<String, EvergreenService> evergreenServiceMap =
            new ConcurrentHashMap<>();
    private static final Map<String, EvergreenService> removedDependencies =
            new ConcurrentHashMap<>();

    /**
     * Constructor for EvergreenService.
     *
     * @param topics                 root Configuration topic for this service
     * @param mqttClient             {@link MqttClient}
     * @param deviceConfiguration    {@link DeviceConfiguration}
     * @param deploymentStatusKeeper {@link DeploymentStatusKeeper}
     * @param kernel                 {@link Kernel}
     */
    @Inject
    public FleetStatusService(Topics topics, MqttClient mqttClient, DeviceConfiguration deviceConfiguration,
                              DeploymentStatusKeeper deploymentStatusKeeper, Kernel kernel) {
        super(topics);

        this.mqttClient = mqttClient;
        this.deviceConfiguration = deviceConfiguration;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.kernel = kernel;

        topics.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS)
                .dflt(DEFAULT_PERIODIC_UPDATE_INTERVAL_MS)
                .subscribe((why, newv) ->
                        periodicUpdateIntervalMs = Coerce.toInt(newv));

        //TODO: Get the kernel version once its implemented.
        this.kernelVersion = "1.0.0";
        topics.getContext().addGlobalStateChangeListener((service, oldState, newState) -> {
            // Do not update status of auto-started services.
            if (service.isAutostart()) {
                return;
            }
            logger.atInfo().log("Service name: {}, oldState: {}, newState: {}", service.getName(), oldState, newState);
            evergreenServiceMap.put(service.getName(), service);
        });

        this.deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS,
                this::deploymentStatusChanged, FLEET_STATUS_SERVICE_TOPICS);
        ScheduledExecutorService ses = getContext().get(ScheduledExecutorService.class);
        ses.scheduleWithFixedDelay(this::updatePeriodicFssData, periodicUpdateIntervalMs,
                periodicUpdateIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void updatePeriodicFssData() {
        logger.atInfo().log("Updating FSS data on a periodic basis.");
        Map<String, EvergreenService> evergreenServiceMap = new HashMap<>();

        this.kernel.orderedDependencies().forEach(evergreenService -> {
            // Do not update status of auto-started services.
            if (!evergreenService.isAutostart()) {
                evergreenServiceMap.put(evergreenService.getName(), evergreenService);
            }
        });
        updateFleetStatusServiceData(evergreenServiceMap);
    }

    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        String status = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS).toString();
        if (JobStatus.IN_PROGRESS.toString().equals(status)) {
            return true;
        }
        logger.atInfo().log("Updating Fleet Status service for job with ID: {}",
                deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID).toString());
        this.kernel.orderedDependencies().forEach(evergreenService -> {
            removedDependencies.remove(evergreenService.getName());
        });
        removedDependencies.forEach(this.evergreenServiceMap::putIfAbsent);
        updateFleetStatusServiceData(this.evergreenServiceMap);
        return true;
    }

    private void updateFleetStatusServiceData(Map<String, EvergreenService> evergreenServiceMap) {
        // If there are no evergreen services to be updated, do not send an update.
        if (evergreenServiceMap.size() == 0) {
            return;
        }

        String thingName = Coerce.toString(this.deviceConfiguration.getThingName());
        String updateFssDataTopic = FLEET_STATUS_SERVICE_PUBLISH_TOPIC.replace("{thingName}", thingName);
        List<ComponentStatusDetails> components = new ArrayList<>();
        AtomicReference<OverAllStatus> overAllStatus = new AtomicReference<>();
        overAllStatus.set(OverAllStatus.HEALTHY);
        Set<String> groupNamesSet = new HashSet<>();

        evergreenServiceMap.forEach((serviceName, service) -> {
            AtomicReference<String> arnString = new AtomicReference<>("");

            try {
                EvergreenService deploymentService = this.kernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
                Topics groupsToRootPackages =
                        deploymentService.getConfig().lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS);
                groupsToRootPackages.iterator().forEachRemaining(groupNode -> {
                    Topics groupTopics = (Topics) groupNode;
                    String groupName = groupTopics.getName();
                    groupNamesSet.add(groupName);

                    groupTopics.iterator().forEachRemaining(pkgNode -> {
                        Topics pkgTopics = (Topics) pkgNode;
                        if (pkgTopics.getName().equals(service.getName())) {
                            Topic lookup = pkgTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY);
                            String groupVersion = (String) lookup.getOnce();
                            arnString.set(Arn.builder()
                                    .withPartition(FLEET_STATUS_ARN_PARTITION)
                                    .withService(FLEET_STATUS_ARN_SERVICE)
                                    .withAccountId(Coerce.toString(deviceConfiguration.getAccountId()))
                                    .withRegion(Coerce.toString(deviceConfiguration.getAWSRegion()))
                                    .withResource(String.format(FLEET_STATUS_ARN_RESOURCE_PREFIX, groupName,
                                            groupVersion))
                                    .build().toString());
                        }
                    });
                });
            } catch (ServiceLoadException e) {
                logger.atError().cause(e);
            }

            Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
            ComponentStatusDetails componentStatusDetails = ComponentStatusDetails.builder()
                    .componentName(service.getName())
                    .state(service.getState())
                    .version(Coerce.toString(versionTopic))
                    .fleetConfigArn(arnString.get())
                    .build();
            components.add(componentStatusDetails);
            if (State.BROKEN.equals(componentStatusDetails.getState())
                    || State.ERRORED.equals(componentStatusDetails.getState())) {
                overAllStatus.set(OverAllStatus.UNHEALTHY);
            }
        });

        evergreenServiceMap.clear();
        removedDependencies.clear();
        String thingGroups = groupNamesSet.stream().map(String::valueOf).collect(Collectors.joining(","));
        FleetStatusDetails fleetStatusDetails = FleetStatusDetails.builder()
                .overAllStatus(overAllStatus.get())
                .componentStatusDetails(components)
                .architecture(System.getProperty("os.arch"))
                .platform(System.getProperty("os.name"))
                .thing(thingName)
                .thingGroups(thingGroups)
                .ggcVersion(this.kernelVersion)
                .sequenceNumber(sequenceNumber.getAndIncrement())
                .build();
        try {
            this.mqttClient.publish(PublishRequest.builder()
                    .qos(QualityOfService.AT_LEAST_ONCE)
                    .topic(updateFssDataTopic)
                    .payload(SERIALIZER.writeValueAsBytes(fleetStatusDetails)).build());
        } catch (ExecutionException | InterruptedException | TimeoutException | JsonProcessingException e) {
            logger.atError().cause(e);
        }
    }

    @Override
    public void startup() {
        logger.atInfo().log("Starting Fleet status service.");
        reportState(State.RUNNING);
    }

    @Override
    public void shutdown() {
        logger.atInfo().log("Stopping Fleet status service.");
    }

    /**
     * Update the removed dependency packages.
     *
     * @param removedEgDependencies set of removed dependencies.
     */
    public void updateRemovedDependencies(Set<EvergreenService> removedEgDependencies) {
        removedEgDependencies.forEach(evergreenService -> {
            removedDependencies.put(evergreenService.getName(), evergreenService);
        });
    }
}
