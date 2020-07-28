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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final MqttClient mqttClient;
    private final DeviceConfiguration deviceConfiguration;
    private final Kernel kernel;
    private final String architecture;
    private final String platform;
    private final ScheduledFuture<?> periodicUpdateFuture;
    private final DeploymentStatusKeeper deploymentStatusKeeper;
    private static final Map<String, EvergreenService> evergreenServiceMap =
            new ConcurrentHashMap<>();
    private Map<String, Set<String>> packageToGroupsMappingCache;
    private static final Map<String, EvergreenService> removedDependencies =
            new ConcurrentHashMap<>();
    private int periodicUpdateIntervalMs;
    private boolean isDeploymentInProgress;

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
        this.architecture = System.getProperty("os.arch");
        this.platform = System.getProperty("os.name");

        topics.lookup(PARAMETERS_CONFIG_KEY, FLEET_STATUS_PERIODIC_UPDATE_INTERVAL_MS)
                .dflt(DEFAULT_PERIODIC_UPDATE_INTERVAL_MS)
                .subscribe((why, newv) ->
                        periodicUpdateIntervalMs = Coerce.toInt(newv));

        //TODO: Get the kernel version once its implemented.
        this.kernelVersion = "1.0.0";
        topics.getContext().addGlobalStateChangeListener(this::handleServiceStateChange);
        this.deploymentStatusKeeper.registerDeploymentStatusConsumer(Deployment.DeploymentType.IOT_JOBS,
                this::deploymentStatusChanged, FLEET_STATUS_SERVICE_TOPICS);
        ScheduledExecutorService ses = getContext().get(ScheduledExecutorService.class);
        this.periodicUpdateFuture = ses.scheduleWithFixedDelay(this::updatePeriodicFssData, periodicUpdateIntervalMs,
                periodicUpdateIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void handleServiceStateChange(EvergreenService evergreenService, State oldState,
                                                       State newState) {
        // Do not update status of auto-started services.
        if (evergreenService.isAutostart()) {
            return;
        }
        logger.atTrace().log("Service name: {}, oldState: {}, newState: {}",
                evergreenService.getName(), oldState, newState);
        evergreenServiceMap.put(evergreenService.getName(), evergreenService);

        // if there is no ongoing deployment and we encounter a BROKEN component, update the fleet status as UNHEALTHY.
        if (!isDeploymentInProgress && (newState.equals(State.BROKEN) || newState.equals(State.ERRORED))) {
            updateFleetStatusServiceData(evergreenServiceMap, OverAllStatus.UNHEALTHY);
        }
    }

    private void updatePeriodicFssData() {
        // Do not update periodic updates if there is an ongoing deployment.
        if (isDeploymentInProgress) {
            logger.atInfo().log("Not updating FSS data on a periodic basis since there is an ongoing deployment.");
            return;
        }
        logger.atInfo().log("Updating FSS data on a periodic basis.");
        Map<String, EvergreenService> evergreenServiceMap = new HashMap<>();
        AtomicReference<OverAllStatus> overAllStatus = new AtomicReference<>();
        overAllStatus.set(OverAllStatus.HEALTHY);

        // Get all running services from the kernel to update the fleet status.
        this.kernel.orderedDependencies().forEach(evergreenService -> {
            // Do not update status of auto-started services.
            if (!evergreenService.isAutostart()) {
                evergreenServiceMap.put(evergreenService.getName(), evergreenService);
            }
            overAllStatus.set(getOverallStatusBasedOnServiceState(evergreenService));
        });
        updateFleetStatusServiceData(evergreenServiceMap, overAllStatus.get());
    }

    @SuppressWarnings("PMD.NullAssignment")
    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        String status = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS).toString();
        if (JobStatus.IN_PROGRESS.toString().equals(status)) {
            isDeploymentInProgress = true;
            packageToGroupsMappingCache = null;
            return true;
        }
        logger.atInfo().log("Updating Fleet Status service for deployment job with ID: {}",
                deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID).toString());
        isDeploymentInProgress = false;
        AtomicReference<OverAllStatus> overAllStatus = new AtomicReference<>();
        overAllStatus.set(OverAllStatus.HEALTHY);

        // Check if the removed dependency is still running (Probably as a dependant service to another service).
        // If so, then remove it from the removedDependencies collection.
        this.kernel.orderedDependencies().forEach(evergreenService -> {
            removedDependencies.remove(evergreenService.getName());
            overAllStatus.set(getOverallStatusBasedOnServiceState(evergreenService));
        });

        // Add all the removed dependencies to the collection of services to update.
        removedDependencies.forEach(evergreenServiceMap::putIfAbsent);
        updateFleetStatusServiceData(evergreenServiceMap, overAllStatus.get());
        return true;
    }

    private void updateFleetStatusServiceData(Map<String, EvergreenService> evergreenServiceMap,
                                              OverAllStatus overAllStatus) {
        // If there are no evergreen services to be updated, do not send an update.
        if (evergreenServiceMap.isEmpty()) {
            return;
        }

        String thingName = Coerce.toString(this.deviceConfiguration.getThingName());
        String updateFssDataTopic = FLEET_STATUS_SERVICE_PUBLISH_TOPIC.replace("{thingName}", thingName);
        List<ComponentStatusDetails> components = new ArrayList<>();
        Set<String> groupNamesSet = new HashSet<>();
        Map<String, Set<String>> pkgToGroupMap = getPackagesToGroupsMapping(groupNamesSet);

        evergreenServiceMap.forEach((serviceName, service) -> {
            String thingGroups = pkgToGroupMap.getOrDefault(serviceName, new HashSet<>()).stream()
                    .map(String::valueOf).collect(Collectors.joining(","));

            Topic versionTopic = service.getServiceConfig().findLeafChild(KernelConfigResolver.VERSION_CONFIG_KEY);
            ComponentStatusDetails componentStatusDetails = ComponentStatusDetails.builder()
                    .componentName(service.getName())
                    .state(service.getState())
                    .version(Coerce.toString(versionTopic))
                    .fleetConfigArn(thingGroups)
                    .build();
            components.add(componentStatusDetails);
        });

        evergreenServiceMap.clear();
        removedDependencies.clear();
        String thingGroups = groupNamesSet.stream().map(String::valueOf).collect(Collectors.joining(","));
        FleetStatusDetails fleetStatusDetails = FleetStatusDetails.builder()
                .overAllStatus(overAllStatus)
                .componentStatusDetails(components)
                .architecture(this.architecture)
                .platform(this.platform)
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

    private Map<String, Set<String>> getPackagesToGroupsMapping(Set<String> groupNamesSet) {
        if (packageToGroupsMappingCache != null) {
            return packageToGroupsMappingCache;
        }
        packageToGroupsMappingCache = new ConcurrentHashMap<>();
        List<String> pendingPackagesList = new LinkedList<>();

        try {
            EvergreenService deploymentService2 = this.kernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
            Topics groupsToRootPackages2 =
                    deploymentService2.getConfig().lookupTopics(GROUP_TO_ROOT_COMPONENTS_TOPICS);

            // Get all the groups associated to the root components.
            groupsToRootPackages2.iterator().forEachRemaining(groupNode -> {
                Topics groupTopics = (Topics) groupNode;
                String groupName = groupTopics.getName();
                groupNamesSet.add(groupName);

                groupTopics.iterator().forEachRemaining(pkgNode -> {
                    Topics pkgTopics = (Topics) pkgNode;
                    Topic lookup = pkgTopics.lookup(GROUP_TO_ROOT_COMPONENTS_GROUP_VERSION_KEY);
                    String groupVersion = (String) lookup.getOnce();
                    String groupName2 = Arn.builder()
                            .withPartition(FLEET_STATUS_ARN_PARTITION)
                            .withService(FLEET_STATUS_ARN_SERVICE)
                            .withAccountId(Coerce.toString(deviceConfiguration.getAccountId()))
                            .withRegion(Coerce.toString(deviceConfiguration.getAWSRegion()))
                            .withResource(String.format(FLEET_STATUS_ARN_RESOURCE_PREFIX, groupName,
                                    groupVersion))
                            .build().toString();
                    Set<String> groupSet = packageToGroupsMappingCache
                            .getOrDefault(pkgTopics.getName(), new HashSet<>());
                    groupSet.add(groupName2);
                    packageToGroupsMappingCache.put(pkgTopics.getName(), groupSet);
                    pendingPackagesList.add(pkgTopics.getName());
                });
            });
        } catch (ServiceLoadException e) {
            return packageToGroupsMappingCache;
        }

        // Associate the groups to the dependant services based on the services it is depending on.
        while (!pendingPackagesList.isEmpty()) {
            String pkgList = pendingPackagesList.get(0);
            try {
                EvergreenService evergreenService = this.kernel.locate(pkgList);
                Set<String> groupName2 = packageToGroupsMappingCache
                        .getOrDefault(evergreenService.getName(), new HashSet<>());

                evergreenService.getDependencies().forEach((evergreenService1, dependencyType) -> {
                    pendingPackagesList.add(evergreenService1.getName());
                    Set<String> groupSet = packageToGroupsMappingCache
                            .getOrDefault(evergreenService1.getName(), new HashSet<>());
                    groupSet.addAll(groupName2);
                    packageToGroupsMappingCache.put(evergreenService1.getName(), groupSet);
                });
            } catch (ServiceLoadException ex) {
                logger.atError().cause(ex);
            }
            pendingPackagesList.remove(0);
        }
        return packageToGroupsMappingCache;
    }

    private OverAllStatus getOverallStatusBasedOnServiceState(EvergreenService evergreenService) {
        if (State.BROKEN.equals(evergreenService.getState())
                || State.ERRORED.equals(evergreenService.getState())) {
            return OverAllStatus.UNHEALTHY;
        }
        return OverAllStatus.HEALTHY;
    }


    @Override
    public void startup() {
        logger.atInfo().log("Starting Fleet status service.");
        reportState(State.RUNNING);
    }

    @SuppressWarnings("PMD.NullAssignment")
    @Override
    public void shutdown() {
        logger.atInfo().log("Stopping Fleet status service.");
        if (!this.periodicUpdateFuture.isCancelled()) {
            this.periodicUpdateFuture.cancel(true);
        }
        packageToGroupsMappingCache = null;
    }

    /**
     * Update the removed dependency packages.
     *
     * @param removedEgDependencies set of removed dependencies.
     */
    public void updateRemovedDependencies(Set<EvergreenService> removedEgDependencies) {
        removedEgDependencies.forEach(evergreenService ->
                removedDependencies.put(evergreenService.getName(), evergreenService));
    }
}
