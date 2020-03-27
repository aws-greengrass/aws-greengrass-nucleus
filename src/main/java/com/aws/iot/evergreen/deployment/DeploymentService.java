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
import com.aws.iot.evergreen.deployment.exceptions.NonRetryableDeploymentTaskFailureException;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.DependencyResolver;
import com.aws.iot.evergreen.packagemanager.KernelConfigResolver;
import com.aws.iot.evergreen.packagemanager.PackageCache;
import com.aws.iot.evergreen.packagemanager.plugins.LocalPackageStore;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();
    //TODO: Change this to be taken from config or user input. Maybe as part of deployment document
    private static final Path LOCAL_ARTIFACT_SOURCE =
            Paths.get(System.getProperty("user.dir")).resolve("local_artifact_source");
    private static final int MQTT_KEEP_ALIVE_TIMEOUT = (int) Duration.ofSeconds(60).toMillis();
    private static final int MQTT_PING_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();

    public static final String DEVICE_PARAM_THING_NAME = "thingName";
    public static final String DEVICE_PARAM_MQTT_CLIENT_ENDPOINT = "mqttClientEndpoint";
    public static final String DEVICE_PARAM_PRIVATE_KEY_PATH = "privateKeyPath";
    public static final String DEVICE_PARAM_CERTIFICATE_FILE_PATH = "certificateFilePath";
    public static final String DEVICE_PARAM_ROOT_CA_PATH = "rootCaPath";
    public static final String DEPLOYMENT_SERVICE_TOPICS = "DeploymentService";
    public static final String PROCESSED_DEPLOYMENTS_TOPICS = "ProcessedDeployments";

    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID = "JobId";
    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS = "JobStatus";
    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS = "StatusDetails";
    private static final String PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_EXECUTION_NUMBER = "JobExecutionNumber";

    @Inject
    private ExecutorService executorService;
    @Inject
    private Kernel kernel;
    @Inject
    private IotJobsHelperFactory iotJobsHelperFactory;
    @Inject
    private DependencyResolver dependencyResolver;
    @Inject
    private PackageCache packageCache;
    @Inject
    private KernelConfigResolver kernelConfigResolver;

    private IotJobsHelper iotJobsHelper;
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private boolean errored;
    @Getter
    private Future<Void> currentProcessStatus = null;
    private String currentJobId;
    private Long currentJobExecutionNumber;
    private AtomicBoolean isConnectedToCloud = new AtomicBoolean(false);
    private AtomicBoolean isConnectionReset = new AtomicBoolean(false);

    @Setter
    private long pollingFrequency = DEPLOYMENT_POLLING_FREQUENCY;

    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            //TODO: what about error code 0
            if (errorCode != 0) {
                logger.atWarn().kv("error", CRT.awsErrorString(errorCode)).log("Connection interrupted");
                isConnectedToCloud.set(false);
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.atInfo().kv("session", (sessionPresent ? "existing session" : "clean session"))
                    .log("Connection resumed: ");
            isConnectedToCloud.set(true);
            isConnectionReset.set(true);
        }
    };

    private final Consumer<JobExecutionsChangedEvent> eventHandler = event -> {
        /*
         * This message is received when either of these things happen
         * 1. Last job completed (successful/failed)
         * 2. A new job was queued
         * 3. A job was cancelled
         * This message receives the list of Queued and InProgress jobs at the time of this message
         */
        Map<JobStatus, List<JobExecutionSummary>> jobs = event.jobs;
        if (jobs.containsKey(JobStatus.QUEUED)) {
            //Do not wait on the future in this async handler,
            //as it will block the thread which establishes
            // the MQTT connection. This will result in frozen MQTT connection
            this.iotJobsHelper.requestNextPendingJobDocument();
        }
    };

    /**
     * Handler that gets invoked when a job description is received.
     * Next pending job description is requested when an mqtt message
     * is published using {@Code requestNextPendingJobDocument} in {@link IotJobsHelper}
     */
    private final Consumer<DescribeJobExecutionResponse> describeJobExecutionResponseConsumer = response -> {
        if (response.execution == null) {
            return;
        }
        JobExecutionData jobExecutionData = response.execution;
        if (jobExecutionData.status == JobStatus.IN_PROGRESS) {
            //TODO: Check the currently running process,
            // if it is same as this jobId then do nothing. If not then there is something wrong
            return;
        } else if (jobExecutionData.status == JobStatus.QUEUED) {
            if (currentJobId == jobExecutionData.jobId) {
                //This job has been picked up and is in progress, the status in cloud is not updated yet
                return;
            }
            //If there is a job running at this time, then it has been canceled in cloud and should be attempted to
            // be canceled here
            if (currentProcessStatus != null && !currentProcessStatus.cancel(true)) {
                //If the cancel is not successful
                return;
            }
            currentJobId = jobExecutionData.jobId;
            currentJobExecutionNumber = jobExecutionData.executionNumber;
            logger.atInfo().log("Received job description for job id : {} and status {}", currentJobId,
                    jobExecutionData.status);
            storeDeploymentStatusInConfig(currentJobId, JobStatus.IN_PROGRESS, currentJobExecutionNumber,
                    new HashMap<>());
            updateStatusOfPersistedDeployments();
            DeploymentTask deploymentTask;
            deploymentTask = new DeploymentTask(dependencyResolver, packageCache, kernelConfigResolver, kernel, logger,
                    response.execution.jobDocument);
            currentProcessStatus = executorService.submit(deploymentTask);
            logger.atInfo().kv("JobId", currentJobId).log("Submitted the job");
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
     * @param topics               The configuration coming from  kernel
     * @param iotJobsHelperFactory Factory object for creating IotJobHelper
     * @param executorService      Executor service coming from kernel
     * @param kernel               The evergreen kernel
     * @param dependencyResolver   {@link DependencyResolver}
     * @param packageCache         {@link PackageCache}
     * @param kernelConfigResolver {@link KernelConfigResolver}
     */
    public DeploymentService(Topics topics, IotJobsHelperFactory iotJobsHelperFactory, ExecutorService executorService,
                             Kernel kernel, DependencyResolver dependencyResolver, PackageCache packageCache,
                             KernelConfigResolver kernelConfigResolver) {
        super(topics);
        this.iotJobsHelperFactory = iotJobsHelperFactory;
        this.executorService = executorService;
        this.kernel = kernel;
        this.dependencyResolver = dependencyResolver;
        this.packageCache = packageCache;
        this.kernelConfigResolver = kernelConfigResolver;
    }


    @Override
    public void startup() {
        try {
            logger.info("Starting up the Deployment Service");
            // Reset shutdown signal since we're trying to startup here
            this.receivedShutdown.set(false);
            try {
                initializeAndConnectToAWSIot();
            } catch (DeviceConfigurationException e) {
                //Since there is no device configuration, device should still be able to perform local deployments
                logger.atWarn().setCause(e).log("Device not configured to communicate with AWS Iot Cloud"
                        + "Device will now operate in offline mode");
            } catch (ConnectionUnavailableException e) {
                logger.atWarn().setCause(e).log("Connectivity issue while communicating with AWS Iot cloud."
                        + "Device will now operate in offline mode");
            }
            reportState(State.RUNNING);
            logger.info("Running deployment service");

            while (!receivedShutdown.get() && !errored) {
                performNewConnectionOperations();
                if (currentProcessStatus != null) {
                    logger.info("Getting the status of the current process");
                    try {
                        //No timeout is set here. Detection of error is delegated to downstream components like
                        // dependency resolver, package downloader, kernel which will have more visibility if something
                        // is going wrong
                        currentProcessStatus.get();
                        storeDeploymentStatusInConfig(currentJobId, JobStatus.SUCCEEDED, currentJobExecutionNumber,
                                new HashMap<>());
                    } catch (ExecutionException e) {
                        logger.atError().kv("JobId", currentJobId).setCause(e)
                                .log("Caught exception while getting the status of the Job");
                        Throwable t = e.getCause();
                        if (t instanceof NonRetryableDeploymentTaskFailureException) {
                            HashMap<String, String> statusDetails = new HashMap<>();
                            statusDetails.put("error", t.getMessage());
                            storeDeploymentStatusInConfig(currentJobId, JobStatus.FAILED, currentJobExecutionNumber,
                                    statusDetails);
                        }
                        //TODO: resubmit the job in case of RetryableDeploymentTaskFailureException
                    }
                    currentProcessStatus = null;
                }
                Thread.sleep(pollingFrequency);
                //This is placed here to provide best chance of updating the IN_PROGRESS status
                //There is still a possibility that this thread starts waiting for the job to finish before
                //updating the status of job to IN_PROGRESS
                updateStatusOfPersistedDeployments();
            }
        } catch (InterruptedException e) {
            logger.atWarn().log("Interrupted while running deployment service");
            //TODO: Perform any cleanup that needs to be done
            reportState(State.FINISHED);
        } catch (AWSIotException e) {
            //This is a non transient exception and requires customer's attention
            logger.atError().log("Caught an exception from AWS Iot cloud");
            //TODO: Revisit if erroring the service is the correct behavior
            errored = true;
            reportState(State.ERRORED);
        }
    }

    @Override
    public void shutdown() {
        receivedShutdown.set(true);
        if (iotJobsHelper != null) {
            iotJobsHelper.closeConnection();
        }
    }

    private void initializeAndConnectToAWSIot()
            throws DeviceConfigurationException, InterruptedException, AWSIotException, ConnectionUnavailableException {
        //TODO: Update then pacakge store to be used once it is designed. Probably remove this.
        this.dependencyResolver.setStore(new LocalPackageStore(LOCAL_ARTIFACT_SOURCE));

        isConnectedToCloud.set(false);
        //TODO: Get it from bootstrap config. Path of Bootstrap config should be taken as argument to kernel?
        String thingName = getStringParameterFromConfig(DEVICE_PARAM_THING_NAME);
        String privateKeyPath = kernel.deTilde(getStringParameterFromConfig(DEVICE_PARAM_PRIVATE_KEY_PATH));
        String certificateFilePath = kernel.deTilde(getStringParameterFromConfig(DEVICE_PARAM_CERTIFICATE_FILE_PATH));
        String rootCAPath = kernel.deTilde(getStringParameterFromConfig(DEVICE_PARAM_ROOT_CA_PATH));
        String clientEndpoint = getStringParameterFromConfig(DEVICE_PARAM_MQTT_CLIENT_ENDPOINT);
        validateDeviceConfiguration(thingName, certificateFilePath, privateKeyPath, rootCAPath, clientEndpoint);
        try {
            this.iotJobsHelper = iotJobsHelperFactory
                    .getIotJobsHelper(thingName, certificateFilePath, privateKeyPath, rootCAPath, clientEndpoint,
                            callbacks);
        } catch (Exception e) {
            throw new DeviceConfigurationException("Device not configured for communicating with Iot Jobs", e);
        }
        logger.atInfo().kv("privateKeyPath", privateKeyPath).kv("certificatePath", certificateFilePath)
                .kv("rootCAPath", rootCAPath).kv("MqttEndpoint", clientEndpoint).kv("thingName", thingName)
                .log("Device configuration successfully read");
        try {
            //TODO: Add retry logic in case of Throttling, Timeout
            iotJobsHelper.connectToAwsIot();
        } catch (ExecutionException e) {
            // connect throws MqttException when port number is wrong.
            // Verified that when network is not available the it throws MqttException.
            // If the endpoint is not correct it again throws MqttException with same error message -
            // `Host name was invalid for dns resolution.`
            //TODO: Distinguish between no network vs invalid mqtt endpoint.
            if (e.getCause() instanceof MqttException) {
                throw new ConnectionUnavailableException("Unable to connect to Iot cloud", e);
            }
            throw new AWSIotException("Caught exception while connecting to Iot Cloud", e);
        } catch (TimeoutException e) {
            throw new ConnectionUnavailableException("Timed out while connecting to Iot Cloud", e);
        }
        isConnectedToCloud.set(true);
        isConnectionReset.set(true);
        logger.atInfo().log("Deployment service initialized successfully to communicate with Iot cloud");
    }

    /**
     * Perform all the operation needed when Mqtt connection to Iot cloud is reset.
     */
    private void performNewConnectionOperations() throws InterruptedException, AWSIotException {
        if (isConnectionReset.get()) {
            try {
                subscribeToJobsTopics();
            } catch (ConnectionUnavailableException e) {
                logger.atWarn().log("No connection available during subscribing to topic. "
                        + "Will retry when connection is available");
            }
            isConnectionReset.set(false);
        }
    }

    private void subscribeToJobsTopics() throws InterruptedException, AWSIotException, ConnectionUnavailableException {
        if (isConnectedToCloud.get() && isConnectionReset.get()) {
            try {
                //TODO: Add retry in case of Throttling, Timeout and LimitExceed exception
                iotJobsHelper.subscribeToEventNotifications(eventHandler);
                iotJobsHelper.subscribeToGetNextJobDescription(describeJobExecutionResponseConsumer, rejectedError -> {
                    logger.error("Job subscription got rejected", rejectedError);
                    //TODO: Add retry logic for subscribing
                });
                iotJobsHelper.requestNextPendingJobDocument();
            } catch (ExecutionException e) {
                //TODO: Verify if network is not available then it will throw MqttException
                if (e.getCause() instanceof MqttException) {
                    throw new ConnectionUnavailableException(e);
                }
                //After the max retries have been exhausted or this is a non retryable exception
                throw new AWSIotException("Caught exception while subscribing to Iot Jobs topics", e);
            } catch (TimeoutException e) {
                //After the max retries have been exhausted
                throw new ConnectionUnavailableException("Timed out while subscribing to Iot Jobs topics", e);
            }
        }
    }

    private void updateStatusOfPersistedDeployments() {
        new Thread(()->{
            if (!isConnectedToCloud.get()) {
                logger.atInfo().log("Not connected to cloud so cannot udpate the status of deployments");
                return;
            }
            synchronized(this.config) {
                Topics processedDeployments = this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
                ArrayList<Topic> deployments = new ArrayList<>();
                processedDeployments.forEach(d->deployments.add((Topic)d));
                //Topics are stored as ConcurrentHashMaps which do not guarantee ordering of elements
                ArrayList<Topic> sortedByTimestamp =
                        (ArrayList<Topic>) deployments.stream().sorted(new Comparator<Topic>() {
                            @Override
                            public int compare(Topic o1, Topic o2) {
                                if(Long.valueOf(o1.getModtime()) > Long.valueOf(o2.getModtime())) {
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
                        iotJobsHelper.updateJobStatus(jobId, JobStatus.valueOf(status), (Long) deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_EXECUTION_NUMBER),
                                (HashMap<String, String>) deploymentDetails.get(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS));
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof MqttException) {
                            //caused due to connectivity issue
                            logger.atWarn().log("Caught exception while updating job status");
                            break;
                        }
                        //This happens when job status update gets rejected from the Iot Cloud
                        //Want to remove this job from the list and continue updating others
                        logger.atError().kv("Status", status).kv("JobId", jobId).setCause(e).log("Job status update rejected");
                    } catch (TimeoutException e) {
                        //assuming this is due to network issue
                        logger.info("Timed out while updating the job status");
                        break;
                    } catch (InterruptedException e) {
                        logger.atWarn().kv("JobId", jobId).kv("Status", status).log("Caught exception while updating the job status");
                    }
                    processedDeployments.remove(topic);
                }
            }
        }).start();

    }

    private void storeDeploymentStatusInConfig(String jobId, JobStatus status, Long executionNumber,
                                               HashMap<String, String> statusDetails) {
        synchronized (this.config) {
            logger.atInfo().kv("JobId", jobId).kv("JobStatus", status).log("Storing job status");
            Topics processedDeployments = this.config.createInteriorChild(PROCESSED_DEPLOYMENTS_TOPICS);
            Map<String, Object> deploymentDetails = new HashMap<>();
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_ID, jobId);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_STATUS, status);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_STATUS_DETAILS, statusDetails);
            deploymentDetails.put(PERSISTED_DEPLOYMENT_STATUS_KEY_JOB_EXECUTION_NUMBER, executionNumber);
            //Each status update is uniquely stored
            Topic thisJob = processedDeployments.createLeafChild(String.valueOf(System.currentTimeMillis()));
            thisJob.setValue(deploymentDetails);
        }
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Topic childTopic = config.findLeafChild(parameterName);
        if (childTopic != null && childTopic.getOnce() != null) {
            paramValue = childTopic.getOnce().toString();
        }
        return paramValue;
    }

    private void validateDeviceConfiguration(String thingName, String certificateFilePath, String privateKeyPath,
                                             String rootCAPath, String clientEndpoint)
            throws DeviceConfigurationException {
        List<String> errors = new ArrayList<>();
        if (thingName != null && thingName.isEmpty()) {
            errors.add("thingName cannot be empty");
        }
        if (certificateFilePath != null && certificateFilePath.isEmpty()) {
            errors.add("certificateFilePath cannot be empty");
        }
        if (privateKeyPath != null && privateKeyPath.isEmpty()) {
            errors.add("privateKeyPath cannot be empty");
        }
        if (rootCAPath != null && rootCAPath.isEmpty()) {
            errors.add("rootCAPath cannot be empty");
        }
        if (clientEndpoint != null && clientEndpoint.isEmpty()) {
            errors.add("clientEndpoint cannot be empty");
        }
        if (!errors.isEmpty()) {
            throw new DeviceConfigurationException(errors.toString());
        }
    }

    public static class IotJobsHelperFactory {

        /**
         * Returns IotJobsHelper {@link IotJobsHelper}.
         *
         * @param thingName           Iot thing name
         * @param certificateFilePath Device certificate file path
         * @param privateKeyPath      Device private key file path
         * @param rootCAPath          Root CA file path
         * @param clientEndpoint      Mqtt endpoint for the customer account
         * @param callbacks           Callback for handling Mqtt connection events
         * @return
         */
        public IotJobsHelper getIotJobsHelper(@NonNull String thingName, @NonNull String certificateFilePath,
                                              @NonNull String privateKeyPath, @NonNull String rootCAPath,
                                              @NonNull String clientEndpoint,
                                              @NonNull MqttClientConnectionEvents callbacks) {

            try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
                 HostResolver resolver = new HostResolver(eventLoopGroup);
                 ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
                 AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder
                         .newMtlsBuilderFromPath(certificateFilePath, privateKeyPath)) {
                builder.withCertificateAuthorityFromPath(null, rootCAPath).withEndpoint(clientEndpoint)
                        .withClientId(UUID.randomUUID().toString()).withCleanSession(true)
                        .withBootstrap(clientBootstrap).withConnectionEventCallbacks(callbacks)
                        .withKeepAliveMs(MQTT_KEEP_ALIVE_TIMEOUT).withPingTimeoutMs(MQTT_PING_TIMEOUT)
                        .withPort((short)8883);

                MqttClientConnection connection = builder.build();
                IotJobsClient iotJobsClient = new IotJobsClient(connection);
                return new IotJobsHelper(thingName, connection, iotJobsClient);
            }
        }
    }
}
