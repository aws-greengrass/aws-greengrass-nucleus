package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Configuration;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.Setter;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.inject.Inject;

import static com.aws.iot.evergreen.deployment.DeploymentService.DEPLOYMENT_SERVICE_TOPICS;
import static com.aws.iot.evergreen.deployment.model.Deployment.DeploymentType;
import static com.aws.iot.evergreen.kernel.EvergreenService.SERVICES_NAMESPACE_TOPIC;

public class DeploymentStatusKeeper {

    public static final String PROCESSED_DEPLOYMENTS_TOPICS = "ProcessedDeployments";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID = "JobId";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS = "JobStatus";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_TYPE = "JobType";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS = "StatusDetails";
    private static final String JOB_ID_LOG_KEY_NAME = "JobId";
    private static Logger logger = LogManager.getLogger(DeploymentStatusKeeper.class);

    @Inject
    @Setter
    private Configuration config;

    private Topics processedDeployments;

    private final Map<DeploymentType, Function<Map<String, Object>, Boolean>> deploymentStatusConsumer
            = new ConcurrentHashMap<>();

    /**
     * Register call backs for receiving deployment status updates for a particular deployment type .
     * @param type determines which deployment type the call back consumes
     * @param consumer deployment status details
     * @return true if call back is registered.
     */
    public boolean registerDeploymentStatusConsumer(DeploymentType type, Function<Map<String, Object>,
            Boolean> consumer) {
        return deploymentStatusConsumer.putIfAbsent(type, consumer) == null;
    }

    /**
     * Persist deployment status in kernel config.
     * @param jobId id for the deployment.
     * @param deploymentType type of deployment.
     * @param status status of deployment.
     * @param statusDetails other details of deployment status.
     */
    public void persistAndPublishDeploymentStatus(String jobId, DeploymentType deploymentType,
                                                  JobStatus status, Map<String, String> statusDetails) {
        // no need to persist status for local deployment
        if (deploymentType.equals(DeploymentType.LOCAL)) {
            return;
        }

        Topics processedDeployments = getProcessedDeployments();
        //While this method is being run, another thread could be running the publishPersistedStatusUpdates
        // method which consumes the data in config from the same topics. These two thread needs to be synchronized
        synchronized (processedDeployments) {
            logger.atDebug().kv(JOB_ID_LOG_KEY_NAME, jobId).kv("JobStatus", status).log("Storing job status");
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, jobId);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, status);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS, statusDetails);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_TYPE, deploymentType);
            //Each status update is uniquely stored
            Topic thisJob = processedDeployments.createLeafChild(String.valueOf(System.currentTimeMillis()));
            thisJob.withValue(deploymentDetails);

            if (getConsumerForDeploymentType(deploymentType).apply(deploymentDetails)) {
                processedDeployments.remove(thisJob);
            }
        }
    }

    /**
     * Invokes the call-backs with persisted deployment status updates for deployments with specified type.
     * This is called by IotJobsHelper/MqttJobsHelper when connection is re-established to update cloud of all
     * all deployments the device performed when offline
     * @param type deployment type
     */
    public void publishPersistedStatusUpdates(DeploymentType type) {
        Topics processedDeployments = getProcessedDeployments();
        //TODO: better sync approach.
        synchronized (processedDeployments) {
            ArrayList<Topic> deployments = new ArrayList<>();
            processedDeployments.forEach(topic -> {

                Map<String, Object> deploymentDetails = (HashMap) ((Topic) topic).getOnce();
                DeploymentType deploymentType = (DeploymentType)
                        deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_TYPE);
                if (deploymentType.equals(type)) {
                    deployments.add((Topic) topic);
                }
            });
            // Topics are stored as ConcurrentHashMaps which do not guarantee ordering of elements
            // We want the statuses to be updated in the cloud in the order in which they were processed on the device.
            // This will be accurate representation of what happened on the device, especially when deployment service
            // processes multiple deployments in the order in which they come. Additionally, a customer workflow can
            // depend on this order. If Group2 gets successfully updated before Group1 then customer workflow may
            // error out.
            List<Topic> sortedByTimestamp = deployments.stream().sorted((o1, o2) -> {
                if (Long.valueOf(o1.getModtime()) > Long.valueOf(o2.getModtime())) {
                    return 1;
                }
                return -1;
            }).collect(Collectors.toList());

            Iterator iterator = sortedByTimestamp.iterator();
            while (iterator.hasNext()) {
                Topic topic = (Topic) iterator.next();
                Map<String, Object> deploymentDetails = (HashMap) topic.getOnce();
                DeploymentType deploymentType = (DeploymentType)
                        deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_TYPE);

                if (getConsumerForDeploymentType(deploymentType).apply(deploymentDetails)) {
                    processedDeployments.remove(topic);
                }
            }
        }
    }

    protected Function<Map<String, Object>, Boolean> getConsumerForDeploymentType(DeploymentType type) {
        return deploymentStatusConsumer.computeIfAbsent(type, deploymentType -> {
            logger.atDebug().kv("DeploymentType", deploymentType)
                    .log("Consumer for type not found, dropping status update");
            return (status) -> true;
        });
    }

    protected Topics getProcessedDeployments() {
        if (processedDeployments == null) {
            processedDeployments = config.lookupTopics(SERVICES_NAMESPACE_TOPIC,
                    DEPLOYMENT_SERVICE_TOPICS, PROCESSED_DEPLOYMENTS_TOPICS);
        }
        return processedDeployments;
    }
}
