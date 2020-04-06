/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.deployment.exceptions.AWSIotException;
import com.aws.iot.evergreen.deployment.exceptions.ConnectionUnavailableException;
import com.aws.iot.evergreen.deployment.exceptions.DeviceConfigurationException;
import com.aws.iot.evergreen.deployment.model.Deployment;
import com.aws.iot.evergreen.deployment.model.DeviceConfiguration;
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.NoArgsConstructor;
import lombok.Setter;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.MqttException;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.inject.Inject;

@NoArgsConstructor
public class IotJobsHelper {

    protected static final String UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC =
            "$aws/things/{thingName}/jobs/{jobId}/update/accepted";
    protected static final String UPDATE_SPECIFIC_JOB_REJECTED_TOPIC =
            "$aws/things/{thingName}/jobs/{jobId}/update/rejected";

    private static final int MQTT_KEEP_ALIVE_TIMEOUT = (int) Duration.ofSeconds(60).toMillis();
    private static final int MQTT_PING_TIMEOUT = (int) Duration.ofSeconds(30).toMillis();
    //The time within which device expects an acknowledgement from Iot cloud after publishing an MQTT message
    //This value needs to be revisited and set to more realistic numbers
    private static final long TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS = (long) Duration.ofMinutes(5).getSeconds();
    //The time it takes for device to publish a message
    //This value needs to be revisited and set to more realistic numbers
    private static final long TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS = (long) Duration.ofMinutes(1).getSeconds();

    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final String JOB_ID_LOG_KEY_NAME = "JobId";
    private static final String CONNECTION_NOT_ESTABLISHED_WARNING_MESSAGE =
            "Connection not established with Iot cloud. First establish connection to AWS Iot";

    private static Logger logger = LogManager.getLogger(IotJobsHelper.class);

    @Inject
    private DeviceConfigurationHelper deviceConfigurationHelper;

    @Inject
    @Setter
    private AWSIotMqttConnectionFactory awsIotMqttConnectionFactory;

    @Setter
    private LinkedBlockingQueue<Deployment> deploymentsQueue;

    private String thingName;
    private IotJobsClient iotJobsClient;
    private MqttClientConnection connection;
    private boolean isConnectionEstablished = false;

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
            requestNextPendingJobDocument();
        }
        //TODO: If there was only one job, then indicate cancellation of that job.
        // Empty list will be received.
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

        logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobExecutionData.jobId).kv("Status", jobExecutionData.status)
                .log("Received Iot job description", jobExecutionData.jobId, jobExecutionData.status);

        String documentString;
        try {
            documentString = OBJECT_MAPPER.writeValueAsString(jobExecutionData.jobDocument);
        } catch (JsonProcessingException e) {
            //TODO: Handle when job document is incorrect json.
            // This should not happen as we are converting a HashMap
            return;
        }
        Deployment deployment =
                new Deployment(documentString, Deployment.DeploymentType.IOT_JOBS, jobExecutionData.jobId);

        if (deploymentsQueue.offer(deployment)) {
            logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobExecutionData.jobId).log("Added the job to the queue");
        }
    };


    /**
     * Constructor for unit testing.
     *
     * @param mqttClientConnection Mqtt client connection already setup
     * @param iotJobsClient        Iot Jobs client using the mqtt connection
     */
    IotJobsHelper(MqttClientConnection mqttClientConnection, IotJobsClient iotJobsClient,
                  DeviceConfigurationHelper deviceConfigurationHelper) throws DeviceConfigurationException {
        this.connection = mqttClientConnection;
        this.iotJobsClient = iotJobsClient;
        this.deviceConfigurationHelper = deviceConfigurationHelper;
        this.thingName = deviceConfigurationHelper.getDeviceConfiguration().getThingName();
        isConnectionEstablished = true;
    }

    public static class AWSIotMqttConnectionFactory {
        /**
         * Get the mqtt connection from device configuration.
         *
         * @param deviceConfiguration The device configuration {@link DeviceConfiguration}
         * @param callbacks           Mqtt callbacks invoked on connection events
         * @return {@link MqttClientConnection}
         */
        public MqttClientConnection getAwsIotMqttConnection(DeviceConfiguration deviceConfiguration,
                                                            MqttClientConnectionEvents callbacks) {
            try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
                 HostResolver resolver = new HostResolver(eventLoopGroup);
                 ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
                 AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder
                         .newMtlsBuilderFromPath(deviceConfiguration.getCertificateFilePath(),
                                 deviceConfiguration.getPrivateKeyFilePath())) {
                builder.withCertificateAuthorityFromPath(null, deviceConfiguration.getRootCAFilePath())
                        //TODO: With MQTT proxy this will change
                        .withEndpoint(deviceConfiguration.getMqttClientEndpoint())
                        .withClientId(UUID.randomUUID().toString()).withCleanSession(true)
                        .withBootstrap(clientBootstrap).withConnectionEventCallbacks(callbacks)
                        .withKeepAliveMs(MQTT_KEEP_ALIVE_TIMEOUT).withPingTimeoutMs(MQTT_PING_TIMEOUT);
                return builder.build();
            }
        }
    }

    /**
     * Connects to AWS Iot Cloud.
     *
     * @param deploymentsQueue The queue to which add the {@link DeploymentTask}
     * @param callbacks        The callback methods to call when connection interruption or resume happens
     * @throws InterruptedException           if interrupted while connecting
     * @throws DeviceConfigurationException   if the device is not configured to communicate with AWS Iot cloud
     * @throws AWSIotException                when an exception occurs in AWS Iot mqtt broker
     * @throws ConnectionUnavailableException if the connection to AWS Iot cloud is not available
     */
    public void connectToAwsIot(LinkedBlockingQueue deploymentsQueue, MqttClientConnectionEvents callbacks)
            throws InterruptedException, DeviceConfigurationException, AWSIotException, ConnectionUnavailableException {

        DeviceConfiguration deviceConfiguration = deviceConfigurationHelper.getDeviceConfiguration();
        connection = awsIotMqttConnectionFactory.getAwsIotMqttConnection(deviceConfiguration, callbacks);
        try {
            //TODO: Add retry for Throttling, Limit exceed exception
            connection.connect().get(TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ConnectionUnavailableException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MqttException) {
                throw new ConnectionUnavailableException(e);
            }
            throw new AWSIotException(e);
        }
        this.iotJobsClient = new IotJobsClient(connection);
        this.deploymentsQueue = deploymentsQueue;
        this.thingName = deviceConfiguration.getThingName();
        isConnectionEstablished = true;
        logger.atInfo().log("Connection established to Iot cloud");
    }


    /**
     * Closes the Mqtt connection.
     */
    public void closeConnection() {
        if (connection != null && !connection.isNull()) {
            connection.close();
        }
    }

    /**
     * Subscribes to the topic which recevies confirmation message of Job update for a given JobId.
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
        if (!isConnectionEstablished) {
            logger.atWarn().log(CONNECTION_NOT_ESTABLISHED_WARNING_MESSAGE);
            return;
        }
        UpdateJobExecutionSubscriptionRequest subscriptionRequest = new UpdateJobExecutionSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        subscriptionRequest.jobId = jobId;
        CompletableFuture<Void> gotResponse = new CompletableFuture<>();
        iotJobsClient.SubscribeToUpdateJobExecutionAccepted(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.atInfo().kv(JOB_ID_LOG_KEY_NAME, jobId).kv("Status", status)
                            .log("Job status updated accepted");
                    String acceptTopicForJobId = UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace("{thingName}", thingName)
                            .replace("{jobId}", jobId);
                    connection.unsubscribe(acceptTopicForJobId);
                    gotResponse.complete(null);
                });

        iotJobsClient.SubscribeToUpdateJobExecutionRejected(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.atWarn().kv(JOB_ID_LOG_KEY_NAME, jobId).kv("Status", status)
                            .log("Job status updated rejected");
                    String rejectTopicForJobId = UPDATE_SPECIFIC_JOB_REJECTED_TOPIC.replace("{thingName}", thingName)
                            .replace("{jobId}", jobId);
                    connection.unsubscribe(rejectTopicForJobId);
                    //TODO: Can this be due to duplicate messages being sent for the job?
                    gotResponse.completeExceptionally(new Exception(response.message));
                });

        UpdateJobExecutionRequest updateJobRequest = new UpdateJobExecutionRequest();
        updateJobRequest.jobId = jobId;
        updateJobRequest.status = status;
        updateJobRequest.statusDetails = statusDetailsMap;
        updateJobRequest.thingName = thingName;
        try {
            iotJobsClient.PublishUpdateJobExecution(updateJobRequest, QualityOfService.AT_LEAST_ONCE).get();
        } catch (ExecutionException e) {
            gotResponse.completeExceptionally(e.getCause());
        }
        gotResponse.get(TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Request the job description of the next available job for this Iot Thing.
     * It publishes on the $aws/things/{thingName}/jobs/$next/get topic.
     */
    public void requestNextPendingJobDocument() {
        if (!isConnectionEstablished) {
            logger.atWarn().log(CONNECTION_NOT_ESTABLISHED_WARNING_MESSAGE);
            return;
        }
        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = thingName;
        describeJobExecutionRequest.jobId = "$next";
        describeJobExecutionRequest.includeJobDocument = true;
        //This method is specifically called from an async event notification handler. Async handler cannot block on
        // this future as that will freeze the MQTT connection.
        iotJobsClient.PublishDescribeJobExecution(describeJobExecutionRequest, QualityOfService.AT_LEAST_ONCE);
    }

    /**
     * Subscribe to the mqtt topics needed for getting Iot Jobs notifications.
     *
     * @throws InterruptedException           When operation is interrupted
     * @throws AWSIotException                When there is an exception from the Iot cloud
     * @throws ConnectionUnavailableException When connection to cloud is not available
     */
    public void subscribeToJobsTopics() throws InterruptedException, AWSIotException, ConnectionUnavailableException {
        if (!isConnectionEstablished) {
            logger.atWarn().log(CONNECTION_NOT_ESTABLISHED_WARNING_MESSAGE);
            return;
        }
        try {
            //TODO: Add retry in case of Throttling, Timeout and LimitExceed exception
            subscribeToEventNotifications(eventHandler);
            subscribeToGetNextJobDescription(describeJobExecutionResponseConsumer, rejectedError -> {
                logger.error("Job subscription got rejected", rejectedError);
                //TODO: Add retry logic for subscribing
            });
            requestNextPendingJobDocument();
        } catch (ExecutionException e) {
            //TODO: If network is not available then it will throw MqttException
            // If there is any other problem like thingName is not specified in the request then also
            // it throws Mqtt exception. Need to distinguish between what is cause due to network unavailability
            // and what is caused by other non-transient causes like invalid parameters
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
                                                    Consumer<RejectedError> consumerReject)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (!isConnectionEstablished) {
            logger.atWarn().log(CONNECTION_NOT_ESTABLISHED_WARNING_MESSAGE);
            return;
        }
        DescribeJobExecutionSubscriptionRequest describeJobExecutionSubscriptionRequest =
                new DescribeJobExecutionSubscriptionRequest();
        describeJobExecutionSubscriptionRequest.thingName = thingName;
        describeJobExecutionSubscriptionRequest.jobId = "$next";
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToDescribeJobExecutionAccepted(describeJobExecutionSubscriptionRequest,
                        QualityOfService.AT_LEAST_ONCE, consumerAccept);
        subscribed.get(TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS, TimeUnit.SECONDS);
        subscribed = iotJobsClient.SubscribeToDescribeJobExecutionRejected(describeJobExecutionSubscriptionRequest,
                QualityOfService.AT_LEAST_ONCE, consumerReject);
        subscribed.get(TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Subscribe to $aws/things/{thingName}/jobs/notify topic.
     *
     * @param eventHandler The handler which run when an event is received
     * @throws ExecutionException   When subscribe failed with an exception
     * @throws InterruptedException When this thread was interrupted
     * @throws TimeoutException     if the operation does not complete within the given time
     */
    protected void subscribeToEventNotifications(Consumer<JobExecutionsChangedEvent> eventHandler)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (!isConnectionEstablished) {
            logger.atWarn().log(CONNECTION_NOT_ESTABLISHED_WARNING_MESSAGE);
            return;
        }
        JobExecutionsChangedSubscriptionRequest request = new JobExecutionsChangedSubscriptionRequest();
        request.thingName = thingName;
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToJobExecutionsChangedEvents(request, QualityOfService.AT_LEAST_ONCE, eventHandler);
        subscribed.get(TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS, TimeUnit.SECONDS);
    }
}
