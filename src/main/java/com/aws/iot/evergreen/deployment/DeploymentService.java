/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.deployment.exceptions.ConnectionUnavailableException;
import com.aws.iot.evergreen.deployment.exceptions.InvalidRequestException;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.exceptions.RetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.deployment.model.DeploymentResult;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.aws.iot.evergreen.util.Utils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {

    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";
    public static final String PROCESSED_DEPLOYMENTS_TOPICS = "ProcessedDeployments";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID = "JobId";
    public static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS = "JobStatus";
    public static final String UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG = "Timed out while updating the job status";
    public static final String UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG = "Caught exception while updating job status";

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    // TODO: These should probably become configurable parameters eventually
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();
    private static final int DEPLOYMENT_MAX_ATTEMPTS = 3;

    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS = "StatusDetails";
    private static final String JOB_ID_LOG_KEY_NAME = "JobId";

    @Inject
    @Setter
    private ExecutorService executorService;
    @Inject
    private DependencyResolver dependencyResolver;
    @Inject
    private PackageManager packageManager;
    @Inject
    private KernelConfigResolver kernelConfigResolver;
    @Inject
    private DeploymentConfigMerger deploymentConfigMerger;
    @Inject
    private IotJobsHelper iotJobsHelper;
    @Inject
    private LocalDeploymentListener localDeploymentListener;

    @Getter
    private Future<DeploymentResult> currentProcessStatus = null;

    // This is very likely not thread safe. If the Deployment Service is split into multiple threads in a re-design
    // as mentioned in some other comments, this will need an update as well
    private String currentDeploymentId = null;
    private Deployment.DeploymentType currentDeploymentType = null;

    private final AtomicInteger currentJobAttemptCount = new AtomicInteger(0);
    private DeploymentTask currentDeploymentTask = null;

    @Getter
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;
    private LinkedBlockingQueue<Deployment> deploymentsQueue = new LinkedBlockingQueue<>();

    final MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            //TODO: what about error code 0
            if (errorCode != 0) {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
                //TODO: Detect this using secondary mechanisms like checking if internet is availalble
                // instead of using ping to Mqtt server. Mqtt ping is expensive and should be used as the last resort.
            }
        }

        @Override
        @SuppressWarnings("PMD.UselessParentheses")
        public void onConnectionResumed(boolean sessionPresent) {
            logger.atInfo().kv("sessionPresent", (sessionPresent ? "true" : "false")).log("Connection resumed");
            runInSeparateThread(() -> {
                subscribeToIotJobTopics();
                updateStatusOfPersistedDeployments();
            });
        }
    };

    /**
     * Constructor.
     *
     * @param topics the configuration coming from kernel
     */
    public DeploymentService(Topics topics) {
        super(topics);
    }

    /**
     * Constructor for unit testing.
     *
     * @param topics                    The configuration coming from  kernel
     * @param executorService           Executor service coming from kernel
     * @param dependencyResolver        {@link DependencyResolver}
     * @param packageManager            {@link PackageManager}
     * @param kernelConfigResolver      {@link KernelConfigResolver}
     * @param deploymentConfigMerger    {@link DeploymentConfigMerger}
     * @param iotJobsHelper             {@link IotJobsHelper}
     * @param localDeploymentListener   {@link LocalDeploymentListener}
     */
    DeploymentService(Topics topics, ExecutorService executorService, DependencyResolver dependencyResolver,
                      PackageManager packageManager, KernelConfigResolver kernelConfigResolver,
                      DeploymentConfigMerger deploymentConfigMerger, IotJobsHelper iotJobsHelper,
                      LocalDeploymentListener localDeploymentListener) {
        super(topics);
        this.executorService = executorService;
        this.dependencyResolver = dependencyResolver;
        this.packageManager = packageManager;
        this.kernelConfigResolver = kernelConfigResolver;
        this.deploymentConfigMerger = deploymentConfigMerger;
        this.iotJobsHelper = iotJobsHelper;
        this.localDeploymentListener = localDeploymentListener;
    }


    @Override
    public void startup() throws InterruptedException {
        logger.info("Starting up the Deployment Service");
        // Reset shutdown signal since we're trying to startup here
        this.receivedShutdown.set(false);
        //Cannot put this in constructor since DI injects dependencies after the instance of object is created.
        // Will revisit the DI to make this better
        iotJobsHelper.setDeploymentsQueue(deploymentsQueue);
        iotJobsHelper.setCallbacks(callbacks);
        localDeploymentListener.setDeploymentsQueue(deploymentsQueue);

        reportState(State.RUNNING);
        logger.info("Running deployment service");

        while (!receivedShutdown.get()) {
            if (currentProcessStatus != null && currentProcessStatus.isDone()) {
                finishCurrentDeployment();
            }
            //Cannot wait on queue because need to listen to queue as well as the currentProcessStatus future.
            //One thread cannot wait on both. If we want to make this completely event driven then we need to put
            // the waiting on currentProcessStatus in its own thread. I currently choose to not do this.
            Deployment deployment = deploymentsQueue.poll();
            if (deployment != null) {
                if (currentDeploymentId != null) {
                    if (deployment.getId().equals(currentDeploymentId)
                            && deployment.getDeploymentType().equals(currentDeploymentType)) {
                        //Duplicate message and already processing this deployment so nothing is needed
                        continue;
                    } else {
                        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentId).log("Canceling the job");
                        //Assuming cancel will either cancel the current job or wait till it finishes
                        cancelCurrentDeployment();
                    }
                }
                createNewDeployment(deployment);
            }
            Thread.sleep(pollingFrequency);
        }
    }

    @Override
    public void shutdown() throws InterruptedException {
        receivedShutdown.set(true);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void finishCurrentDeployment() throws InterruptedException {
        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentId).log("Current deployment finished");
        try {
            // No timeout is set here. Detection of error is delegated to downstream components like
            // dependency resolver, package downloader, kernel which will have more visibility
            // if something is going wrong
            DeploymentResult result = currentProcessStatus.get();
            if (result != null) {
                DeploymentResult.DeploymentStatus deploymentStatus = result.getDeploymentStatus();
                Map<String, String> statusDetails = new HashMap<>();
                statusDetails.put("detailed-deployment-status", deploymentStatus.name());
                if (deploymentStatus.equals(DeploymentResult.DeploymentStatus.SUCCESSFUL)) {
                    storeDeploymentStatusInConfig(currentDeploymentId, currentDeploymentType,
                            JobStatus.SUCCEEDED, statusDetails);
                } else {
                    if (result.getFailureCause() != null) {
                        statusDetails.put("deployment-failure-cause", result.getFailureCause().toString());
                    }
                    storeDeploymentStatusInConfig(currentDeploymentId, currentDeploymentType,
                            JobStatus.FAILED, statusDetails);
                }
            }
            currentJobAttemptCount.set(0);
        } catch (ExecutionException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, currentDeploymentId).setCause(e)
                    .log("Caught exception while getting the status of the Job");
            Throwable t = e.getCause();
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", t.getMessage());
            if (t instanceof NonRetryableDeploymentTaskFailureException
                    || currentJobAttemptCount.get() >= DEPLOYMENT_MAX_ATTEMPTS) {
                storeDeploymentStatusInConfig(currentDeploymentId, currentDeploymentType,
                        JobStatus.FAILED, statusDetails);
                currentJobAttemptCount.set(0);
            } else if (t instanceof RetryableDeploymentTaskFailureException) {
                // Resubmit task, increment attempt count and return
                currentProcessStatus = executorService.submit(currentDeploymentTask);
                currentJobAttemptCount.incrementAndGet();
                return;
            }
        }
        // Setting this to null to indicate there is not current deployment being processed
        // Did not use optionals over null due to performance
        currentProcessStatus = null;
        currentDeploymentId = null;
        updateStatusOfPersistedDeployments();
    }

    @SuppressWarnings("PMD.NullAssignment")
    private void cancelCurrentDeployment() {
        //TODO: Make the deployment task be able to handle the interrupt
        // and wait till the job gets cancelled or is finished
        if (currentProcessStatus != null) {
            currentProcessStatus.cancel(true);
            currentProcessStatus = null;
            currentDeploymentId = null;
        }
    }


    private void createNewDeployment(Deployment deployment) {
        logger.atInfo().kv("DeploymentId", deployment.getId())
                .kv("DeploymentType", deployment.getDeploymentType().toString())
                .log("Received deployment in the queue");

        DeploymentDocument deploymentDocument;
        try {
            logger.atInfo().kv("document", deployment.getDeploymentDocument())
                    .log("Recevied deployment document in queue");
            deploymentDocument = parseAndValidateJobDocument(deployment.getDeploymentDocument());
        } catch (InvalidRequestException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, deployment.getId())
                    .kv("DeploymentType", deployment.getDeploymentType().toString())
                    .log("Invalid document for deployment");
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", e.getMessage());
            storeDeploymentStatusInConfig(deployment.getId(), deployment.getDeploymentType(),
                    JobStatus.FAILED, statusDetails);
            return;
        }
        switch (deployment.getDeploymentType()) {
            case IOT_JOBS:
                newIotJobsDeployment(deploymentDocument, deployment);
                break;
            case LOCAL:
                newLocalDeployment(deploymentDocument);
                break;
                default:

        }

        currentDeploymentId = deployment.getId();
        currentDeploymentType = deployment.getDeploymentType();
    }

    private void newLocalDeployment(DeploymentDocument deploymentDocument) {

        currentDeploymentTask =
                new DeploymentTask(dependencyResolver, packageManager, kernelConfigResolver, deploymentConfigMerger,
                logger, deploymentDocument);
        currentProcessStatus = executorService.submit(currentDeploymentTask);
    }

    private void newIotJobsDeployment(DeploymentDocument deploymentDocument, Deployment deployment) {

        currentDeploymentTask =
                new DeploymentTask(dependencyResolver, packageManager, kernelConfigResolver, deploymentConfigMerger,
                        logger, deploymentDocument);
        storeDeploymentStatusInConfig(deployment.getId(), deployment.getDeploymentType(),
                JobStatus.IN_PROGRESS, new HashMap<>());
        updateStatusOfPersistedDeployments();
        currentProcessStatus = executorService.submit(currentDeploymentTask);

        // newIotJobsDeployment will only be called at first attempt
        currentJobAttemptCount.set(1);
    }


    private void subscribeToIotJobTopics() {
        try {
            iotJobsHelper.subscribeToJobsTopics();
        } catch (ConnectionUnavailableException e) {
            logger.atWarn().setCause(e).log("No connection available during subscribing to topic. "
                    + "Will retry when connection is available");
        } catch (InterruptedException e) {
            //Since this method can run as runnable cannot throw exception so handling exceptions here
            logger.atWarn().log("Interrupted while running deployment service");
            //TODO: Perform any cleanup that needs to be done
            reportState(State.FINISHED);
        } catch (AWSIotException e) {
            //Device will run in offline mode if it is not able to subscribe to Iot Jobs topics
            logger.atError().setCause(e).log("Caught an exception from AWS Iot cloud");
            //TODO: Revisit if we should erroring the service in this case
        }
    }

    private Future<?> runInSeparateThread(Runnable method) {
        return executorService.submit(method);
    }

    //TODO: Move this to a separate class along with storeDeploymentStatusInConfig.
    private void updateStatusOfPersistedDeployments() {
        Topics processedDeployments = this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
        //This method can be called is a separate thread when mqtt connection resumes. While this happens a
        // deployment can finish and config can get updated with the latest deployment's status using the
        // storeDeploymentStatusInConfig. The two threads use the same topics in the config and thus need to be
        // synchronized
        synchronized (processedDeployments) {
            ArrayList<Topic> deployments = new ArrayList<>();
            processedDeployments.forEach(d -> deployments.add((Topic) d));
            // Topics are stored as ConcurrentHashMaps which do not guarantee ordering of elements
            // We want the statuses to be updated in the cloud in the order in which they were processed on the device.
            // This will be accurate representation of what happened on the device, especially when deployment service
            // processes multiple deployments in the order in which they come. Additionally, a customer workflow can
            // depend on this order. If Group2 gets successfully updated before Group1 then customer workflow may
            // error out.
            List<Topic> sortedByTimestamp =
                    (List<Topic>) deployments.stream().sorted(new Comparator<Topic>() {
                        @Override
                        public int compare(Topic o1, Topic o2) {
                            if (Long.valueOf(o1.getModtime()) > Long.valueOf(o2.getModtime())) {
                                return 1;
                            }
                            return -1;
                        }
                    }).collect(Collectors.toList());

            Iterator iterator = sortedByTimestamp.iterator();
            while (iterator.hasNext()) {
                Topic topic = (Topic) iterator.next();
                Map<String, Object> deploymentDetails = (HashMap) topic.getOnce();
                String jobId = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID).toString();
                String status = deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS).toString();
                logger.atInfo().kv("Modified time", topic.getModtime()).kv(JOB_ID_LOG_KEY_NAME, jobId)
                        .kv("Status", status).kv("StatusDetails",
                        deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS).toString())
                        .log("Updating status of persisted deployment");
                try {
                    //TODO: Use the status updater as per the deployment type. Updating deployment status in
                    // IotJobs is different from updating a deployment coming from device shadow or local deployments.
                    iotJobsHelper.updateJobStatus(jobId, JobStatus.valueOf(status),
                            (HashMap<String, String>) deploymentDetails
                                    .get(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS));
                } catch (ExecutionException e) {
                    if (e.getCause() instanceof MqttException) {
                        //caused due to connectivity issue
                        logger.atWarn().setCause(e).log(UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG);
                        break;
                    }
                    //This happens when job status update gets rejected from the Iot Cloud
                    //Want to remove this job from the list and continue updating others
                    logger.atError().kv("Status", status).kv(JOB_ID_LOG_KEY_NAME, jobId).setCause(e)
                            .log("Job status update rejected");
                } catch (TimeoutException e) {
                    //assuming this is due to network issue
                    logger.info(UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG);
                    break;
                } catch (InterruptedException e) {
                    logger.atWarn().kv(JOB_ID_LOG_KEY_NAME, jobId).kv("Status", status)
                            .log("Got interrupted while updating the job status");
                    break;
                }
                processedDeployments.remove(topic);
            }
        }
    }

    private void storeDeploymentStatusInConfig(String jobId, Deployment.DeploymentType deploymentType,
                                               JobStatus status, Map<String, String> statusDetails) {
        // no need to persist status for local deployment
        if (deploymentType.equals(Deployment.DeploymentType.LOCAL)) {
            return;
        }
        //While this method is being run, another thread could be running the updateStatusOfPersistedDeployments
        // method which consumes the data in config from the same topics. These two thread needs to be synchronized
        synchronized (this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS)) {
            logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobId).kv("JobStatus", status).log("Storing job status");
            Topics processedDeployments = this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, jobId);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, status);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS, statusDetails);
            //TODO: Store the deployment type
            //Each status update is uniquely stored
            Topic thisJob = processedDeployments.createLeafChild(String.valueOf(System.currentTimeMillis()));
            thisJob.withValue(deploymentDetails);
        }
    }

    private DeploymentDocument parseAndValidateJobDocument(String jobDocumentString) throws InvalidRequestException {
        if (Utils.isEmpty(jobDocumentString)) {
            throw new InvalidRequestException("Job document cannot be empty");
        }
        try {
            return OBJECT_MAPPER.readValue(jobDocumentString, DeploymentDocument.class);
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Unable to parse the job document", e);
        }
    }

    void setDeploymentsQueue(LinkedBlockingQueue<Deployment> deploymentsQueue) {
        this.deploymentsQueue = deploymentsQueue;
    }
}
