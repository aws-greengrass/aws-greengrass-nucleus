package com.aws.greengrass.deployment;

import com.aws.greengrass.builtin.services.cli.CLIServiceAgent;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.ipc.services.cli.models.DeploymentStatus;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.util.Coerce;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.aws.greengrass.deployment.model.Deployment.DeploymentType;

public class DeploymentStatusKeeper {

    public static final String PROCESSED_DEPLOYMENTS_TOPICS = "ProcessedDeployments";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID = "JobId";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS = "JobStatus";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE = "DeploymentType";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS = "StatusDetails";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_STATUS = "Status";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_LOCAL_DEPLOYMENT_ID = "deploymentId";
    private static final String JOB_ID_LOG_KEY_NAME = "JobId";
    private static final Logger logger = LogManager.getLogger(DeploymentStatusKeeper.class);

    @Setter
    private DeploymentService deploymentService;

    private Topics processedDeployments;

    private final Map<DeploymentType, Map<String, Function<Map<String, Object>, Boolean>>> deploymentStatusConsumerMap
            = new ConcurrentHashMap<>();

    /**
     * Register call backs for receiving deployment status updates for a particular deployment type .
     *
     * @param type        determines which deployment type the call back consumes
     * @param consumer    deployment status details
     * @param serviceName subscribing service name
     * @return true if call back is registered.
     */
    public boolean registerDeploymentStatusConsumer(DeploymentType type,
                                                    Function<Map<String, Object>, Boolean> consumer,
                                                    String serviceName) {
        Map<String, Function<Map<String, Object>, Boolean>> map = deploymentStatusConsumerMap
                .getOrDefault(type, new ConcurrentHashMap<>());
        map.putIfAbsent(serviceName, consumer);
        return deploymentStatusConsumerMap.put(type, map) == null;
    }

    /**
     * Persist deployment status in kernel config.
     *
     * @param deploymentId   id for the deployment.
     * @param deploymentType type of deployment.
     * @param status         status of deployment.
     * @param statusDetails  other details of deployment status.
     */
    public void persistAndPublishDeploymentStatus(String deploymentId, DeploymentType deploymentType, String status,
                                                  Map<String, String> statusDetails) {

        //While this method is being run, another thread could be running the publishPersistedStatusUpdates
        // method which consumes the data in config from the same topics. These two thread needs to be synchronized
        synchronized (deploymentType) {
            logger.atDebug().kv(JOB_ID_LOG_KEY_NAME, deploymentId).kv("JobStatus", status).log("Storing job status");
            // TODO: Consider making DeploymentDetailsIotJobs and LocalDeploymentDetails inherit from the same base
            //  class with deployment type as common parameter and store those objects directly instead of Map
            Map<String, Object> deploymentDetails = null;
            if (deploymentType == DeploymentType.IOT_JOBS) {
                IotJobsHelper.DeploymentDetailsIotJobs deploymentDetailsIotJobs =
                        new IotJobsHelper.DeploymentDetailsIotJobs();
                deploymentDetailsIotJobs.setJobId(deploymentId);
                deploymentDetailsIotJobs.setJobStatus(JobStatus.valueOf(status));
                deploymentDetailsIotJobs.setStatusDetails(statusDetails);
                deploymentDetailsIotJobs.setDeploymentType(DeploymentType.IOT_JOBS);
                deploymentDetails = deploymentDetailsIotJobs.convertToMapOfObjects();
            } else if (deploymentType == DeploymentType.LOCAL) {
                CLIServiceAgent.LocalDeploymentDetails localDeploymentDetails =
                        new CLIServiceAgent.LocalDeploymentDetails();
                localDeploymentDetails.setDeploymentId(deploymentId);
                localDeploymentDetails.setDeploymentType(Deployment.DeploymentType.LOCAL);
                localDeploymentDetails.setStatus(DeploymentStatus.valueOf(status));
                deploymentDetails = localDeploymentDetails.convertToMapOfObject();
            }
            //Each status update is uniquely stored
            Topics processedDeployments = getProcessedDeployments();
            Topics thisJob = processedDeployments.createInteriorChild(String.valueOf(System.currentTimeMillis()));
            thisJob.replaceAndWait(deploymentDetails);
        }
        publishPersistedStatusUpdates(deploymentType);
    }

    /**
     * Invokes the call-backs with persisted deployment status updates for deployments with specified type.
     * This is called by IotJobsHelper/MqttJobsHelper when connection is re-established to update cloud of all
     * all deployments the device performed when offline
     *
     * @param type deployment type
     */
    public void publishPersistedStatusUpdates(DeploymentType type) {
        synchronized (type) {
            Topics processedDeployments = getProcessedDeployments();
            ArrayList<Topics> deployments = new ArrayList<>();
            processedDeployments.forEach(node -> {
                Topics deploymentDetails = (Topics) node;
                DeploymentType deploymentType = Coerce.toEnum(DeploymentType.class, deploymentDetails
                        .find(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE));
                if (deploymentType.equals(type)) {
                    deployments.add(deploymentDetails);
                }
            });
            // Topics are stored as ConcurrentHashMaps which do not guarantee ordering of elements
            // We want the statuses to be updated in the cloud in the order in which they were processed on the device.
            // This will be accurate representation of what happened on the device, especially when deployment service
            // processes multiple deployments in the order in which they come. Additionally, a customer workflow can
            // depend on this order. If Group2 gets successfully updated before Group1 then customer workflow may
            // error out.
            List<Topics> sortedByTimestamp = deployments.stream().sorted((o1, o2) -> {
                if (o1.getModtime() > o2.getModtime()) {
                    return 1;
                }
                return -1;
            }).collect(Collectors.toList());

            for (Topics topics : sortedByTimestamp) {
                DeploymentType deploymentType =
                        Coerce.toEnum(DeploymentType.class,
                                topics.find(PERSISTED_DEPLOYMENT_STATUS_KEY_DEPLOYMENT_TYPE));

                boolean allConsumersUpdated = getConsumersForDeploymentType(deploymentType).stream()
                        .allMatch(consumer -> consumer.apply(topics.toPOJO()));
                if (!allConsumersUpdated) {
                    // If one deployment update fails, exit the loop to ensure the update order.
                    logger.atDebug().log("Unable to update status of persisted deployments. Retry later");
                    break;
                }
                processedDeployments.remove(topics);
            }
        }
    }

    /**
     * Gets the list of callback functions based on the Deployment Type.
     *
     * @param type the type of deployment. {@link DeploymentType}
     * @return list of callback functions.
     */
    protected List<Function<Map<String, Object>, Boolean>> getConsumersForDeploymentType(DeploymentType type) {
        return new ArrayList<>(deploymentStatusConsumerMap.get(type).values());
    }

    /**
     * Get a reference to persisted deployment states Topics. Not thread-safe.
     *
     * @return Topics of persisted deployment states
     */
    protected Topics getProcessedDeployments() {
        if (processedDeployments == null) {
            processedDeployments = deploymentService.getRuntimeConfig().lookupTopics(PROCESSED_DEPLOYMENTS_TOPICS);
        }
        return processedDeployments;
    }
}
