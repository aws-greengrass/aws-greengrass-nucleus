/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.deployment;

import com.aws.greengrass.config.Node;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.InjectionActions;
import com.aws.greengrass.deployment.exceptions.AWSIotException;
import com.aws.greengrass.deployment.exceptions.ConnectionUnavailableException;
import com.aws.greengrass.deployment.exceptions.DeviceConfigurationException;
import com.aws.greengrass.deployment.model.Deployment;
import com.aws.greengrass.deployment.model.Deployment.DeploymentType;
import com.aws.greengrass.deployment.model.DeploymentTaskMetadata;
import com.aws.greengrass.lifecyclemanager.GreengrassService;
import com.aws.greengrass.lifecyclemanager.Kernel;
import com.aws.greengrass.lifecyclemanager.exceptions.ServiceLoadException;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.mqttclient.MqttClient;
import com.aws.greengrass.mqttclient.WrapperMqttClientConnection;
import com.aws.greengrass.status.FleetStatusService;
import com.aws.greengrass.util.Coerce;
import com.aws.greengrass.util.SerializerFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;

import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_ID_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_DETAILS_KEY_NAME;
import static com.aws.greengrass.deployment.DeploymentStatusKeeper.DEPLOYMENT_STATUS_KEY_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_CERTIFICATE_FILE_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_IOT_DATA_ENDPOINT;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_PRIVATE_KEY_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_ROOT_CA_PATH;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.greengrass.deployment.IotJobsClientWrapper.JOB_DESCRIBE_ACCEPTED_TOPIC;
import static com.aws.greengrass.deployment.IotJobsClientWrapper.JOB_DESCRIBE_REJECTED_TOPIC;
import static com.aws.greengrass.deployment.IotJobsClientWrapper.JOB_EXECUTIONS_CHANGED_TOPIC;
import static com.aws.greengrass.deployment.IotJobsClientWrapper.JOB_UPDATE_ACCEPTED_TOPIC;
import static com.aws.greengrass.deployment.IotJobsClientWrapper.JOB_UPDATE_REJECTED_TOPIC;

@NoArgsConstructor
public class IotJobsHelper implements InjectionActions {

    public static final String UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG = "Timed out while updating the job status";
    public static final String UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG = "Caught exception while updating job status";
    public static final String UPDATE_DEPLOYMENT_STATUS_ACCEPTED = "Job status update was accepted";
    public static final String STATUS_LOG_KEY_NAME = "Status";
    protected static final String SUBSCRIPTION_JOB_DESCRIPTION_RETRY_MESSAGE =
            "No connection available during subscribing to Iot Jobs descriptions topic. Will retry in sometime";
    protected static final String SUBSCRIPTION_JOB_DESCRIPTION_INTERRUPTED =
            "Interrupted while subscribing to Iot Jobs descriptions topic";
    protected static final String SUBSCRIPTION_EVENT_NOTIFICATIONS_RETRY =
            "No connection available during subscribing to Iot jobs event notifications topic. Will retry in sometime";
    protected static final String SUBSCRIPTION_EVENT_NOTIFICATIONS_INTERRUPTED =
            "Interrupted while subscribing to Iot jobs event notifications topic";
    private static final String JOB_ID_LOG_KEY_NAME = "JobId";
    private static final String NEXT_JOB_LITERAL = "$next";
    // Sometimes when we are notified that a new job is queued and request the next pending job document immediately,
    // we get an empty response. This unprocessedJobs is to track the number of new queued jobs that we are notified
    // with, and keep retrying the request until we get a non-empty response.
    private static final AtomicInteger unprocessedJobs = new AtomicInteger(0);
    @Getter(AccessLevel.PACKAGE)
    private static final LatestQueuedJobs latestQueuedJobs = new LatestQueuedJobs();

    private static final Logger logger = LogManager.getLogger(IotJobsHelper.class);
    private static final long WAIT_TIME_MS_TO_SUBSCRIBE_AGAIN = Duration.ofMinutes(2).toMillis();
    private static final Random RANDOM = new Random();

    @Inject
    private DeviceConfiguration deviceConfiguration;

    @Inject
    private IotJobsClientFactory iotJobsClientFactory;

    @Inject
    private WrapperMqttConnectionFactory wrapperMqttConnectionFactory;

    @Inject
    private ExecutorService executorService;

    @Inject
    private DeploymentStatusKeeper deploymentStatusKeeper;

    @Inject
    private FleetStatusService fleetStatusService;

    @Inject
    private Kernel kernel;

    @Inject
    private MqttClient mqttClient;

    @Setter
    @Inject
    private DeploymentQueue deploymentQueue;

    private MqttClientConnection connection;

    @Setter(AccessLevel.PACKAGE) // For tests
    private long waitTimeToSubscribeAgain = WAIT_TIME_MS_TO_SUBSCRIBE_AGAIN;

    @Setter // For tests
    private IotJobsClientWrapper iotJobsClientWrapper;
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
            // Only increment instead of adding number of jobs from the list, because we will get one notification for
            // each new job QUEUED.
            unprocessedJobs.incrementAndGet();
            logger.atInfo().log("Received new deployment notification. Requesting details");
            //Do not wait on the future in this async handler,
            //as it will block the thread which establishes
            // the MQTT connection. This will result in frozen MQTT connection
            requestNextPendingJobDocument();
            return;
        }
        if (jobs.isEmpty()) {
            logger.atInfo().log("Received empty jobs in notification ");
            unprocessedJobs.set(0);
            evaluateCancellationAndCancelDeploymentIfNeeded();
            return;
        }
        logger.atInfo().kv("jobs", jobs).log("Received other deployment notification. Not supported yet");
    };
    /**
     * Handler that gets invoked when a job description is received.
     * Next pending job description is requested when an mqtt message
     * is published using {@Code requestNextPendingJobDocument} in {@link IotJobsHelper}
     */
    private final Consumer<DescribeJobExecutionResponse> describeJobExecutionResponseConsumer = response -> {
        if (response.execution == null) {
            logger.atInfo().log("No deployment job found");
            if (unprocessedJobs.get() > 0) {
                // Keep resending request for job details since we got notification of QUEUED jobs
                logger.atDebug().log("Retry requesting next pending job document");
                requestNextPendingJobDocument();
            }
            return;
        }
        if (unprocessedJobs.get() > 0) {
            unprocessedJobs.decrementAndGet();
        }
        JobExecutionData jobExecutionData = response.execution;

        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobExecutionData.jobId).kv(STATUS_LOG_KEY_NAME, jobExecutionData.status)
                .kv("queueAt", jobExecutionData.queuedAt).log("Received Iot job description");
        if (!latestQueuedJobs.addNewJobIfAbsent(jobExecutionData.queuedAt.toInstant(), jobExecutionData.jobId)) {
            logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobExecutionData.jobId)
                    .log("Duplicate or outdated job notification. Ignoring.");
            return;
        }

        String documentString;
        try {
            documentString =
                    SerializerFactory.getFailSafeJsonObjectMapper().writeValueAsString(jobExecutionData.jobDocument);
        } catch (JsonProcessingException e) {
            // This should not happen as we are converting a HashMap
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, jobExecutionData.jobId).setCause(e)
                    .log("Failed to serialize job document");
            return;
        }

        // Reaching this point means there is no IN_PROGRESS job in cloud because if there was, it would
        // have been deduplicated. The fact that we got the next queued deployment means that the previous
        // IN_PROGRESS job is either finished with SUCCEEDED/FAILED status or got cancelled
        // Hence, evaluate if there was a cancellation and if so add a cancellation deployment and
        // then add this next QUEUED job to the deployment queue
        evaluateCancellationAndCancelDeploymentIfNeeded();

        // Add the job queued in cloud to the deployment queue so it's picked up on its turn
        Deployment deployment =
                new Deployment(documentString, DeploymentType.IOT_JOBS, jobExecutionData.jobId);

        if (deploymentQueue.offer(deployment)) {
            logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobExecutionData.jobId).log("Added the job to the queue");
        }
    };
    @Getter
    public MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            executorService.execute(() -> {
                requestNextPendingJobDocument();
                deploymentStatusKeeper.publishPersistedStatusUpdates(DeploymentType.IOT_JOBS);
            });
        }
    };

    /**
     * Constructor for unit testing.
     */
    IotJobsHelper(DeviceConfiguration deviceConfiguration,
                  IotJobsClientFactory iotJobsClientFactory,
                  DeploymentQueue deploymentQueue,
                  DeploymentStatusKeeper deploymentStatusKeeper,
                  ExecutorService executorService,
                  Kernel kernel,
                  WrapperMqttConnectionFactory wrapperMqttConnectionFactory,
                  MqttClient mqttClient,
                  FleetStatusService fleetStatusService) {
        this.deviceConfiguration = deviceConfiguration;
        this.iotJobsClientFactory = iotJobsClientFactory;
        this.deploymentQueue = deploymentQueue;
        this.deploymentStatusKeeper = deploymentStatusKeeper;
        this.executorService = executorService;
        this.kernel = kernel;
        this.wrapperMqttConnectionFactory = wrapperMqttConnectionFactory;
        this.mqttClient = mqttClient;
        this.fleetStatusService = fleetStatusService;
    }

    private static void unwrapExecutionException(ExecutionException e)
            throws TimeoutException, InterruptedException, ExecutionException {
        Throwable cause = e.getCause();
        if (cause instanceof TimeoutException) {
            throw (TimeoutException) cause;
        }
        if (cause instanceof InterruptedException) {
            throw (InterruptedException) cause;
        }
        throw e;
    }

    @Override
    public void postInject() {
        deviceConfiguration.onAnyChange((what, node) -> {
            if (node != null && what.equals(WhatHappened.childChanged) && relevantNodeChanged(node)) {
                try {
                    connectToIotJobs(deviceConfiguration);
                } catch (DeviceConfigurationException e) {
                    logger.atWarn().log("Device not configured to talk to AWS Iot cloud. "
                            + "IOT job deployment is offline: {}", e.getMessage());
                    return;
                }
            }
        });

        try {
            connectToIotJobs(deviceConfiguration);
        } catch (DeviceConfigurationException e) {
            logger.atWarn().log("Device not configured to talk to AWS Iot cloud. IOT job deployment is offline: {}",
                    e.getMessage());
            return;
        }
    }

    private boolean relevantNodeChanged(Node node) {
        // List of configuration nodes that we need to reconfigure for if they change
        return node.childOf(DEVICE_PARAM_THING_NAME) || node.childOf(DEVICE_PARAM_IOT_DATA_ENDPOINT)
                || node.childOf(DEVICE_PARAM_PRIVATE_KEY_PATH)
                || node.childOf(DEVICE_PARAM_CERTIFICATE_FILE_PATH) || node.childOf(DEVICE_PARAM_ROOT_CA_PATH)
                || node.childOf(DEVICE_PARAM_AWS_REGION);
    }

    private void connectToIotJobs(DeviceConfiguration deviceConfiguration)
            throws DeviceConfigurationException {

        // Not using isDeviceConfiguredToTalkToCloud() in order to provide the detailed error message to user
        deviceConfiguration.validate();
        setupCommWithIotJobs();
    }

    private void setupCommWithIotJobs() {
        mqttClient.addToCallbackEvents(callbacks);
        this.connection = wrapperMqttConnectionFactory.getAwsIotMqttConnection(mqttClient);

        // GG_NEEDS_REVIEW: TODO: switch back to IotJobsClient after IoT device sdk updated for jobs namespace
        this.iotJobsClientWrapper = iotJobsClientFactory.getIotJobsClientWrapper(connection);
        deploymentStatusKeeper.registerDeploymentStatusConsumer(DeploymentType.IOT_JOBS,
                this::deploymentStatusChanged, IotJobsHelper.class.getName());

        logger.dfltKv("ThingName", (Supplier<String>) () ->
                Coerce.toString(deviceConfiguration.getThingName()));
        executorService.execute(() -> {
            subscribeToJobsTopics();
            logger.atInfo().log("Connection established to IoT cloud");
            deploymentStatusKeeper.publishPersistedStatusUpdates(DeploymentType.IOT_JOBS);
            this.fleetStatusService.updateFleetStatusUpdateForAllComponents();
        });
    }

    private Boolean deploymentStatusChanged(Map<String, Object> deploymentDetails) {
        String jobId = (String) deploymentDetails.get(DEPLOYMENT_ID_KEY_NAME);
        String status = (String) deploymentDetails.get(DEPLOYMENT_STATUS_KEY_NAME);
        Map<String, String> statusDetails = (Map<String, String>)
                deploymentDetails.get(DEPLOYMENT_STATUS_DETAILS_KEY_NAME);
        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobId).kv(STATUS_LOG_KEY_NAME, status).kv("StatusDetails",
                statusDetails).log("Updating status of persisted deployment");
        try {
            updateJobStatus(jobId, JobStatus.valueOf(status), new HashMap<>(statusDetails));
            return true;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MqttException) {
                //caused due to connectivity issue
                logger.atError().setCause(e).kv(STATUS_LOG_KEY_NAME, status)
                        .log(UPDATE_DEPLOYMENT_STATUS_MQTT_ERROR_LOG);
                return false;
            }
            // This happens when job status update gets rejected from the Iot Cloud
            // Want to remove this job from the list and continue updating others
            logger.atError().kv(STATUS_LOG_KEY_NAME, status).kv(JOB_ID_LOG_KEY_NAME, jobId).setCause(e)
                    .log("Job status update rejected");
            return true;
        } catch (TimeoutException e) {
            // assuming this is due to network issue
            logger.info(UPDATE_DEPLOYMENT_STATUS_TIMEOUT_ERROR_LOG);
        } catch (InterruptedException e) {
            logger.atError().kv(JOB_ID_LOG_KEY_NAME, jobId).kv(STATUS_LOG_KEY_NAME, status)
                    .log("Got interrupted while updating the job status");
        }
        return false;
    }

    /**
     * Subscribes to the topic which receives confirmation message of Job update for a given JobId.
     * Updates the status of an Iot Job with given JobId to a given status.
     *
     * @param jobId            The jobId to be updated
     * @param status           The {@link JobStatus} to which to update
     * @param statusDetailsMap map with job status details
     * @throws ExecutionException   if update fails
     * @throws InterruptedException if the thread gets interrupted
     * @throws TimeoutException     if the operation does not complete within the given time
     */
    @SuppressWarnings("PMD.LooseCoupling")
    public void updateJobStatus(String jobId, JobStatus status, HashMap<String, String> statusDetailsMap)
            throws ExecutionException, InterruptedException, TimeoutException {
        UpdateJobExecutionSubscriptionRequest subscriptionRequest = new UpdateJobExecutionSubscriptionRequest();
        String thingName = Coerce.toString(deviceConfiguration.getThingName());
        subscriptionRequest.thingName = thingName;
        subscriptionRequest.jobId = jobId;
        CompletableFuture<Void> gotResponse = new CompletableFuture<>();
        iotJobsClientWrapper.SubscribeToUpdateJobExecutionAccepted(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobId).kv(STATUS_LOG_KEY_NAME, status)
                            .log(UPDATE_DEPLOYMENT_STATUS_ACCEPTED);
                    gotResponse.complete(null);
                });

        iotJobsClientWrapper.SubscribeToUpdateJobExecutionRejected(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.atWarn().kv(JOB_ID_LOG_KEY_NAME, jobId).kv(STATUS_LOG_KEY_NAME, status)
                            .log("Job status updated rejected");
                    gotResponse.completeExceptionally(new Exception(response.message));
                });
        UpdateJobExecutionRequest updateJobRequest = new UpdateJobExecutionRequest();
        updateJobRequest.jobId = jobId;
        updateJobRequest.status = status;
        updateJobRequest.statusDetails = statusDetailsMap;
        updateJobRequest.thingName = thingName;
        try {
            iotJobsClientWrapper.PublishUpdateJobExecution(updateJobRequest, QualityOfService.AT_LEAST_ONCE).get();
        } catch (ExecutionException e) {
            try {
                unwrapExecutionException(e);
            } catch (ExecutionException e1) {
                gotResponse.completeExceptionally(e1.getCause());
            } catch (TimeoutException | InterruptedException e1) {
                gotResponse.completeExceptionally(e1);
            }
        }

        try {
            gotResponse.get(mqttClient.getMqttOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
        } finally {
            // Either got response, or timed out, so unsubscribe from the job topics now
            connection.unsubscribe(String.format(JOB_UPDATE_ACCEPTED_TOPIC, thingName, jobId));
            connection.unsubscribe(String.format(JOB_UPDATE_REJECTED_TOPIC, thingName, jobId));
        }
    }

    /**
     * Request the job description of the next available job for this Iot Thing.
     * It publishes on the $aws/things/{thingName}/jobs/$next/get topic.
     */
    public void requestNextPendingJobDocument() {
        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = Coerce.toString(deviceConfiguration.getThingName());
        describeJobExecutionRequest.jobId = NEXT_JOB_LITERAL;
        describeJobExecutionRequest.includeJobDocument = true;
        //This method is specifically called from an async event notification handler. Async handler cannot block on
        // this future as that will freeze the MQTT connection.
        iotJobsClientWrapper.PublishDescribeJobExecution(describeJobExecutionRequest, QualityOfService.AT_LEAST_ONCE);
        logger.atDebug().log("Requesting the next deployment");
    }

    /**
     * Subscribe to the mqtt topics needed for getting Iot Jobs notifications.
     *
     * @throws InterruptedException           When operation is interrupted
     * @throws AWSIotException                When there is an exception from the Iot cloud
     * @throws ConnectionUnavailableException When connection to cloud is not available
     */
    public void subscribeToJobsTopics() {

        logger.atDebug().log("Subscribing to Iot Jobs Topics");
        subscribeToGetNextJobDescription(describeJobExecutionResponseConsumer, rejectedError -> {
            logger.error("Job subscription got rejected", rejectedError);
        });
        subscribeToEventNotifications(eventHandler);
        // To receive the description of jobs which were created before the subscription was created (before the device
        // came online)
        requestNextPendingJobDocument();
    }

    /**
     * Unsubscribe from Iot Jobs topics.
     */
    public void unsubscribeFromIotJobsTopics() {
        logger.atDebug().log("Unsubscribing from Iot Jobs topics");
        unsubscribeFromEventNotifications();
        unsubscribeFromJobDescription();
    }

    /**
     * Subscribes to the describe job topic to get the job details for the next available pending job
     * It also publishes a message to get the next pending job.
     * It returns an empty message if nothing is available
     *
     * @param consumerAccept Consumer for when the job is accepted
     * @param consumerReject Consumer for when the job is rejected
     * @throws ExecutionException   if subscribing fails
     * @throws InterruptedException if the thread gets interrupted
     * @throws TimeoutException     if the operation does not complete within the given time
     */
    protected void subscribeToGetNextJobDescription(Consumer<DescribeJobExecutionResponse> consumerAccept,
                                                    Consumer<RejectedError> consumerReject) {

        logger.atDebug().log("Subscribing to deployment job execution update.");
        DescribeJobExecutionSubscriptionRequest describeJobExecutionSubscriptionRequest =
                new DescribeJobExecutionSubscriptionRequest();
        describeJobExecutionSubscriptionRequest.thingName = Coerce.toString(deviceConfiguration.getThingName());
        describeJobExecutionSubscriptionRequest.jobId = NEXT_JOB_LITERAL;

        while (true) {
            CompletableFuture<Integer> subscribed = iotJobsClientWrapper
                    .SubscribeToDescribeJobExecutionAccepted(describeJobExecutionSubscriptionRequest,
                            QualityOfService.AT_LEAST_ONCE, consumerAccept);
            try {
                subscribed.get(mqttClient.getMqttOperationTimeoutMillis(), TimeUnit.MILLISECONDS);
                subscribed = iotJobsClientWrapper
                        .SubscribeToDescribeJobExecutionRejected(describeJobExecutionSubscriptionRequest,
                                QualityOfService.AT_LEAST_ONCE, consumerReject);
                subscribed.get(mqttClient.getMqttOperationTimeoutMillis(), TimeUnit.MILLISECONDS);

                logger.atInfo().log("Subscribed to deployment job execution update.");
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MqttException || cause instanceof TimeoutException) {
                    logger.atWarn().setCause(cause).log(SUBSCRIPTION_JOB_DESCRIPTION_RETRY_MESSAGE);
                }
                if (cause instanceof InterruptedException) {
                    logger.atWarn().log(SUBSCRIPTION_JOB_DESCRIPTION_INTERRUPTED);
                    break;
                }
            } catch (TimeoutException e) {
                logger.atWarn().setCause(e).log(SUBSCRIPTION_JOB_DESCRIPTION_RETRY_MESSAGE);
            } catch (InterruptedException e) {
                logger.atWarn().log(SUBSCRIPTION_JOB_DESCRIPTION_INTERRUPTED);
                break;
            }

            try {
                // Wait for sometime and then try to subscribe again
                Thread.sleep(waitTimeToSubscribeAgain + RANDOM.nextInt(10_000));
            } catch (InterruptedException interruptedException) {
                logger.atWarn().log(SUBSCRIPTION_JOB_DESCRIPTION_INTERRUPTED);
                break;
            }
        }
    }

    private void unsubscribeFromJobDescription() {
        if (connection != null) {
            String topic = String.format(JOB_DESCRIBE_ACCEPTED_TOPIC,
                    Coerce.toString(deviceConfiguration.getThingName()), NEXT_JOB_LITERAL);
            connection.unsubscribe(topic);

            topic = String.format(JOB_DESCRIBE_REJECTED_TOPIC,
                    Coerce.toString(deviceConfiguration.getThingName()), NEXT_JOB_LITERAL);
            connection.unsubscribe(topic);
        }
    }

    /**
     * Subscribe to $aws/things/{thingName}/jobs/notify topic.
     *
     * @param eventHandler The handler which run when an event is received
     * @throws ExecutionException   When subscribe failed with an exception
     * @throws InterruptedException When this thread was interrupted
     * @throws TimeoutException     if the operation does not complete within the given time
     */
    protected void subscribeToEventNotifications(Consumer<JobExecutionsChangedEvent> eventHandler) {

        logger.atDebug().log("Subscribing to deployment job event notifications.");
        JobExecutionsChangedSubscriptionRequest request = new JobExecutionsChangedSubscriptionRequest();
        request.thingName = Coerce.toString(deviceConfiguration.getThingName());

        while (true) {
            CompletableFuture<Integer> subscribed = iotJobsClientWrapper.SubscribeToJobExecutionsChangedEvents(request,
                    QualityOfService.AT_LEAST_ONCE, eventHandler);
            try {
                subscribed.get(mqttClient.getMqttOperationTimeoutMillis(), TimeUnit.MILLISECONDS);

                logger.atInfo().log("Subscribed to deployment job event notifications.");
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof MqttException || cause instanceof TimeoutException) {
                    logger.atWarn().setCause(cause).log(SUBSCRIPTION_EVENT_NOTIFICATIONS_RETRY);
                }
                if (cause instanceof InterruptedException) {
                    logger.atWarn().log(SUBSCRIPTION_EVENT_NOTIFICATIONS_INTERRUPTED);
                    break;
                }
            } catch (InterruptedException e) {
                logger.atWarn().log(SUBSCRIPTION_EVENT_NOTIFICATIONS_INTERRUPTED);
                break;
            } catch (TimeoutException e) {
                logger.atWarn().setCause(e).log(SUBSCRIPTION_EVENT_NOTIFICATIONS_RETRY);
            }

            try {
                // Wait for sometime and then try to subscribe again
                Thread.sleep(waitTimeToSubscribeAgain + RANDOM.nextInt(10_000));
            } catch (InterruptedException interruptedException) {
                logger.atWarn().log(SUBSCRIPTION_EVENT_NOTIFICATIONS_INTERRUPTED);
                break;
            }
        }
    }

    private void unsubscribeFromEventNotifications() {
        if (connection != null) {
            String topic = String.format(JOB_EXECUTIONS_CHANGED_TOPIC,
                    Coerce.toString(deviceConfiguration.getThingName()));
            connection.unsubscribe(topic);
        }
    }

    private void evaluateCancellationAndCancelDeploymentIfNeeded() {
        try {
            GreengrassService deploymentServiceLocateResult =
                    kernel.locate(DeploymentService.DEPLOYMENT_SERVICE_TOPICS);
            if (deploymentServiceLocateResult instanceof DeploymentService) {
                DeploymentService deploymentService = (DeploymentService) deploymentServiceLocateResult;
                DeploymentTaskMetadata currentDeployment = deploymentService.getCurrentDeploymentTaskMetadata();

                // If the queue is not empty then it means deployment(s) from other sources is/are queued in it,
                // in that case don't add a cancellation deployment because it can't be added to the front of the queue
                // we will just have to let current deployment finish
                Deployment deployment = new Deployment(DeploymentType.IOT_JOBS, UUID.randomUUID().toString(), true);
                if (deploymentQueue.isEmpty() && currentDeployment != null && currentDeployment.isCancellable()
                        && DeploymentType.IOT_JOBS.equals(currentDeployment.getDeploymentType())
                        && deploymentQueue.offer(deployment)) {
                    logger.atInfo().log("Added cancellation deployment to the queue");
                }
            }
        } catch (ServiceLoadException e) {
            logger.atError().setCause(e).log("Failed to find deployment service");
        }
    }

    public static class WrapperMqttConnectionFactory {
        public WrapperMqttClientConnection getAwsIotMqttConnection(MqttClient mqttClient) {
            return new WrapperMqttClientConnection(mqttClient);
        }
    }

    public static class IotJobsClientFactory {
        public IotJobsClientWrapper getIotJobsClientWrapper(MqttClientConnection connection) {
            return new IotJobsClientWrapper(connection);
        }
    }

    static class LatestQueuedJobs {
        private final Set<String> jobIds = new HashSet<>();
        // Used to track deployment jobs which involve kernel restart, when QueueAt information is not available.
        private final Set<String> lastProcessedJobIds = new HashSet<>();
        private Instant lastQueueAt = Instant.EPOCH;

        /**
         * Track IoT jobs with the latest timestamp.
         *
         * @param queueAt QueueAt timestamp in IoT Job Execution Data
         * @param jobId   IoT job ID
         * @return true if IoT job with the given ID is a new job yet to be processed, false otherwise
         */
        public synchronized boolean addNewJobIfAbsent(Instant queueAt, String jobId) {
            if (lastProcessedJobIds.contains(jobId)) {
                // Duplicate job but now queueAt information is available so track the timestamp in this way.
                trackLastKnownJobs(queueAt, jobId);
                lastProcessedJobIds.remove(jobId);
                return false;
            }
            return trackLastKnownJobs(queueAt, jobId);
        }

        private synchronized boolean trackLastKnownJobs(Instant queueAt, String jobId) {
            if (queueAt.isAfter(lastQueueAt)) {
                lastQueueAt = queueAt;
                jobIds.clear();
                jobIds.add(jobId);
                return true;
            }
            if (queueAt.isBefore(lastQueueAt) || jobIds.contains(jobId)) {
                return false;
            }
            jobIds.add(jobId);
            return true;
        }

        public synchronized void addProcessedJob(String jobId) {
            if (jobIds.contains(jobId)) {
                // One IoT jobs is processed at a time. If the job is already tracked, it's sufficient for de-dupe,
                // so no need to save again.
                return;
            }
            lastProcessedJobIds.add(jobId);
        }
    }
}
