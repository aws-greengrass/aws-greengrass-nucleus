/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.deployment.exceptions.ConnectionUnavailableException;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.deployment.exceptions.InvalidRequestException;
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeploymentDocument;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.Setter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {
    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";
    public static final String PROCESSED_DEPLOYMENTS_TOPICS = "ProcessedDeployments";
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();
    //TODO: Change this to be taken from config or user input. Maybe as part of deployment document
    private static final Path LOCAL_ARTIFACT_SOURCE =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");
    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID = "JobId";
    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS = "JobStatus";
    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS = "StatusDetails";

    @Inject
    private ExecutorService executorService;
    @Inject
    private Kernel kernel;
    @Inject
    private DependencyResolver dependencyResolver;
    @Inject
    private PackageCache packageCache;
    @Inject
    private KernelConfigResolver kernelConfigResolver;
    @Inject
    private IotJobsHelper iotJobsHelper;
    @Getter
    private Future<Void> currentProcessStatus = null;

    private LinkedBlockingQueue<Deployment> deploymentsQueue;
    private String currentJobId = null;
    private boolean errored;
    private AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private AtomicBoolean isConnectedToCloud = new AtomicBoolean(false);

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;

    private final MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            //TODO: what about error code 0
            if (errorCode != 0) {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
                //TODO: Detect this using secondary mechanisms like checking if internet is availalble
                // instead of using ping to Mqtt server. Mqtt ping is expensive and should be used as the last resort.
                isConnectedToCloud.set(false);
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.atInfo().kv("session", (sessionPresent ? "existing session" : "clean session"))
                    .log("Connection resumed: ");
            isConnectedToCloud.set(true);
            runInSeparateThread(() -> subscribeToIotJobTopics());
            runInSeparateThread(() -> updateStatusOfPersistedDeployments());
        }
    };

    /**
     * Constructor.
     *
     * @param topics the configuration coming from kernel
     */
    public DeploymentService(Topics topics) {
        super(topics);
        deploymentsQueue = new LinkedBlockingQueue<>();
    }

    /**
     * Constructor for unit testing.
     *
     * @param topics               The configuration coming from  kernel
     * @param executorService      Executor service coming from kernel
     * @param kernel               The evergreen kernel
     * @param dependencyResolver   {@link DependencyResolver}
     * @param packageCache         {@link PackageCache}
     * @param kernelConfigResolver {@link KernelConfigResolver}
     */
    public DeploymentService(Topics topics, ExecutorService executorService, Kernel kernel,
                             DependencyResolver dependencyResolver, PackageCache packageCache,
                             KernelConfigResolver kernelConfigResolver) {
        super(topics);
        this.executorService = executorService;
        this.kernel = kernel;
        this.dependencyResolver = dependencyResolver;
        this.packageCache = packageCache;
        this.kernelConfigResolver = kernelConfigResolver;
        deploymentsQueue = new LinkedBlockingQueue<>();
    }


    @Override
    public void startup() {
        try {
            logger.info("Starting up the Deployment Service");
            // Reset shutdown signal since we're trying to startup here
            this.receivedShutdown.set(false);
            //TODO: Update/Remove this when package store is implemented.
            this.dependencyResolver.setStore(new LocalPackageStore(LOCAL_ARTIFACT_SOURCE));
            connectToAWSIot();
            reportState(State.RUNNING);
            logger.info("Running deployment service");

            while (!receivedShutdown.get() && !errored) {
                Deployment deployment = null;
                //Cannot wait on queue because need to listen to queue as well as the currentProcessStatus future.
                //One thread cannot wait on both. If we want to make this completely event driven then we need to put
                // the waiting on currentProcessStatus in its own thread. I currently choose to not do this.
                if ((currentProcessStatus != null && currentProcessStatus.isDone())
                        || (deployment = deploymentsQueue.poll()) != null) {
                    if (deployment == null) { //Current job finished
                        finishCurrentDeployment();
                    } else { //Received new deployment
                        createNewDeployment(deployment);
                    }
                }
                Thread.sleep(pollingFrequency);
            }
        } catch (InterruptedException e) {
            logger.atWarn().log("Interrupted while running deployment service");
            //TODO: Perform any cleanup that needs to be done
            reportState(State.FINISHED);
        }
    }

    @Override
    public void shutdown() {
        receivedShutdown.set(true);
        if (iotJobsHelper != null) {
            iotJobsHelper.closeConnection();
        }
    }

    private void connectToAWSIot() throws InterruptedException {
        try {
            //TODO: Separate out making MQTT connection and IotJobs helper when MQTT proxy is used.
            iotJobsHelper.connectToAwsIot(deploymentsQueue, callbacks);
            isConnectedToCloud.set(true);
            iotJobsHelper.subscribeToJobsTopics();
        } catch (DeviceConfigurationException e) {
            //Since there is no device configuration, device should still be able to perform local deploymentsQueue
            logger.atWarn().setCause(e).log("Device not configured to communicate with AWS Iot Cloud"
                    + "Device will now operate in offline mode");
        } catch (ConnectionUnavailableException e) {
            //TODO: Add retry logic to connect again when connection availalble
            logger.atWarn().setCause(e).log("Connectivity issue while communicating with AWS Iot cloud."
                    + "Device will now operate in offline mode");
        } catch (AWSIotException e) {
            //This is a non transient exception and might require customer's attention
            logger.atError().log("Caught an exception from AWS Iot cloud");
            //TODO: Revisit if erroring the service is the correct behavior. Device should go in offline mode
            errored = true;
            reportState(State.ERRORED);
        }
    }

    private void finishCurrentDeployment() throws InterruptedException {
        logger.atInfo().kv("JobId", currentJobId).log("Current deployment finished");
        try {
            //No timeout is set here. Detection of error is delegated to downstream components like
            // dependency resolver, package downloader, kernel which will have more visibility
            // if something is going wrong
            currentProcessStatus.get();
            storeDeploymentStatusInConfig(currentJobId, JobStatus.SUCCEEDED, new HashMap<>());
        } catch (ExecutionException e) {
            logger.atError().kv("JobId", currentJobId).setCause(e)
                    .log("Caught exception while getting the status of the Job");
            Throwable t = e.getCause();
            if (t instanceof NonRetryableDeploymentTaskFailureException) {
                HashMap<String, String> statusDetails = new HashMap<>();
                statusDetails.put("error", t.getMessage());
                storeDeploymentStatusInConfig(currentJobId, JobStatus.FAILED, statusDetails);
            }
            //TODO: resubmit the job in case of RetryableDeploymentTaskFailureException
        }
        currentProcessStatus = null;
        currentJobId = null;
        updateStatusOfPersistedDeployments();
    }

    private void createNewDeployment(Deployment deployment) {
        logger.atInfo().kv("DeploymentId", deployment.getId())
                .kv("DeploymentType", deployment.getDeploymentType().toString())
                .log("Received deployment in the queue");
        //Check if this is for cancellation
        if (this.currentJobId != null && this.currentJobId != deployment.getId()) {
            //TODO: Cancel the current deployment
        }
        currentJobId = deployment.getId();
        DeploymentDocument deploymentDocument;
        try {
            logger.atInfo().kv("document", deployment.getDeploymentDocument())
                    .log("Recevied deployment document in queue");
            deploymentDocument = parseAndValidateJobDocument(deployment.getDeploymentDocument());
        } catch (InvalidRequestException e) {
            logger.atError().kv("JobId", deployment.getId())
                    .kv("DeploymentType", deployment.getDeploymentType().toString())
                    .log("Invalid document for deployment");
            HashMap<String, String> statusDetails = new HashMap<>();
            statusDetails.put("error", e.getMessage());
            storeDeploymentStatusInConfig(deployment.getId(), JobStatus.FAILED, statusDetails);
            return;
        }
        DeploymentTask deploymentTask =
                new DeploymentTask(dependencyResolver, packageCache, kernelConfigResolver, kernel, logger,
                        deploymentDocument);
        storeDeploymentStatusInConfig(deployment.getId(), JobStatus.IN_PROGRESS, new HashMap<>());
        updateStatusOfPersistedDeployments();
        currentProcessStatus = executorService.submit(deploymentTask);
    }

    private void subscribeToIotJobTopics() {
        if (!isConnectedToCloud.get()) {
            logger.atInfo().log("Not connected to cloud so cannot subscribe to topics");
            return;
        }
        try {
            iotJobsHelper.subscribeToJobsTopics();
        } catch (ConnectionUnavailableException e) {
            logger.atWarn().setCause(e).log("No connection available during subscribing to topic. "
                    + "Will retry when connection is available");
        } catch (InterruptedException e) {
            logger.atWarn().log("Got interrupted while updating the job status");
        } catch (AWSIotException e) {
            //Device will run in offline mode if it is not able to subscribe to Iot Jobs topics
            logger.atError("Caught exception while subscribing to Iot jobs topics").setCause(e);
        }
    }

    private void runInSeparateThread(Runnable method) {
        executorService.submit(method);
    }

    private void updateStatusOfPersistedDeployments() {
        if (!isConnectedToCloud.get()) {
            logger.atInfo().log("Not connected to cloud so cannot udpate the status of deploymentsQueue");
            return;
        }
        synchronized (this.config) {
            Topics processedDeployments = this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
            ArrayList<Topic> deployments = new ArrayList<>();
            processedDeployments.forEach(d -> deployments.add((Topic) d));
            //Topics are stored as ConcurrentHashMaps which do not guarantee ordering of elements
            ArrayList<Topic> sortedByTimestamp =
                    (ArrayList<Topic>) deployments.stream().sorted(new Comparator<Topic>() {
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
                logger.atInfo().kv("Modified time", topic.getModtime()).kv("JobId", jobId).kv("Status", status)
                        .kv("StatusDetails",
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
                        logger.atWarn().log("Caught exception while updating job status");
                        break;
                    }
                    //This happens when job status update gets rejected from the Iot Cloud
                    //Want to remove this job from the list and continue updating others
                    logger.atError().kv("Status", status).kv("JobId", jobId).setCause(e)
                            .log("Job status update rejected");
                } catch (TimeoutException e) {
                    //assuming this is due to network issue
                    logger.info("Timed out while updating the job status");
                    break;
                } catch (InterruptedException e) {
                    logger.atWarn().kv("JobId", jobId).kv("Status", status)
                            .log("Got interrupted while updating the job status");
                }
                processedDeployments.remove(topic);
            }
        }
    }

    private void storeDeploymentStatusInConfig(String jobId, JobStatus status, HashMap<String, String> statusDetails) {
        synchronized (this.config) {
            logger.atInfo().kv("JobId", jobId).kv("JobStatus", status).log("Storing job status");
            Topics processedDeployments = this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, jobId);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, status);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS, statusDetails);
            //TODO: Store the deployment type
            //Each status update is uniquely stored
            Topic thisJob = processedDeployments.createLeafChild(String.valueOf(System.currentTimeMillis()));
            thisJob.setValue(deploymentDetails);
        }
    }

    private DeploymentDocument parseAndValidateJobDocument(String jobDocumentString) throws InvalidRequestException {

        if (jobDocumentString == null || jobDocumentString.isEmpty()) {
            throw new InvalidRequestException("Job document cannot be empty");
        }

        try {
            DeploymentDocument deploymentDocument =
                    OBJECT_MAPPER.readValue(jobDocumentString, DeploymentDocument.class);
            return deploymentDocument;
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Unable to parse the job document", e);
        }
    }
}
