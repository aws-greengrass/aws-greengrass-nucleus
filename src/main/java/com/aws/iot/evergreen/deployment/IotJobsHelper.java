/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedEvent;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionsChangedSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

@NoArgsConstructor
public class IotJobsHelper {

    public static final String UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC =
            "$aws/things/{thingName}/jobs/{jobId}/update" + "/accepted";
    public static final String UPDATE_SPECIFIC_JOB_REJECTED_TOPIC =
            "$aws/things/{thingName}/jobs/{jobId}/update" + "/rejected";

    //IotJobsHelper is not in Context, so initializing a new one here. It will be added to context in later iterations
    private static Logger logger = LogManager.getLogger(IotJobsHelper.class);

    private String thingName;
    private IotJobsClient iotJobsClient;
    private MqttClientConnection connection;

    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            if (errorCode != 0) {
                logger.atError().kv("errorCode", CRT.awsErrorString(errorCode)).log("Connection interrupted: ");
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.atInfo().kv("session", (sessionPresent ? "existing session" : "clean session"))
                    .log("Connection resumed: ");
        }
    };

    /**
     * Connects to AWS Iot Cloud.
     *
     * @throws ExecutionException   if connecting fails
     * @throws InterruptedException if interrupted while connecting
     */
    public void connectToAwsIot() throws ExecutionException, InterruptedException {
        connection.connect().get();
        logger.atInfo().log("Connection established to Iot cloud");
    }

    /**
     * Constructor to be used in unit tests.
     *
     * @param thingName            The Iot thing name
     * @param mqttClientConnection Mqtt client connection already setup
     * @param iotJobsClient        Iot Jobs client using the mqtt connection
     */
    public IotJobsHelper(String thingName, MqttClientConnection mqttClientConnection, IotJobsClient iotJobsClient) {
        this.thingName = thingName;
        this.connection = mqttClientConnection;
        this.iotJobsClient = iotJobsClient;
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
     * @param executionNumber  The job execution number
     * @param statusDetailsMap map with job status details
     */
    public CompletableFuture<Integer> updateJobStatus(String jobId, JobStatus status,
                                                      Long executionNumber,
                                                      HashMap<String, String> statusDetailsMap) {
        logger.atDebug().kv("JobId", jobId).kv("Status", status).log("Updating job status");
        UpdateJobExecutionSubscriptionRequest subscriptionRequest = new UpdateJobExecutionSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        subscriptionRequest.jobId = jobId;
        iotJobsClient.SubscribeToUpdateJobExecutionAccepted(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.info("Marked job " + jobId + "as " + status);
                    String topicForJobId = UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace("{thingName}", thingName)
                            .replace("{jobId}", jobId);
                    connection.unsubscribe(topicForJobId);
                });

        iotJobsClient.SubscribeToUpdateJobExecutionRejected(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.error("Job " + jobId + " not updated as " + status);
                    String topicForJobId = UPDATE_SPECIFIC_JOB_REJECTED_TOPIC.replace("{thingName}", thingName)
                            .replace("{jobId}", jobId);
                    //TODO: Add retry for updating the job or throw error
                    connection.unsubscribe(topicForJobId);
                });

        UpdateJobExecutionRequest updateJobRequest = new UpdateJobExecutionRequest();
        updateJobRequest.jobId = jobId;
        updateJobRequest.executionNumber = executionNumber;
        updateJobRequest.status = status;
        updateJobRequest.statusDetails = statusDetailsMap;
        updateJobRequest.thingName = thingName;
        return iotJobsClient.PublishUpdateJobExecution(updateJobRequest, QualityOfService.AT_LEAST_ONCE);
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
     */
    public void subscribeToGetNextJobDecription(Consumer<DescribeJobExecutionResponse> consumerAccept,
                                                Consumer<RejectedError> consumerReject)
            throws ExecutionException, InterruptedException {
        logger.info("Subscribing to next job description");

        DescribeJobExecutionSubscriptionRequest describeJobExecutionSubscriptionRequest =
                new DescribeJobExecutionSubscriptionRequest();
        describeJobExecutionSubscriptionRequest.thingName = thingName;
        describeJobExecutionSubscriptionRequest.jobId = "$next";
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToDescribeJobExecutionAccepted(describeJobExecutionSubscriptionRequest,
                        QualityOfService.AT_LEAST_ONCE, consumerAccept);
        subscribed.get();
        iotJobsClient.SubscribeToDescribeJobExecutionRejected(describeJobExecutionSubscriptionRequest,
                QualityOfService.AT_LEAST_ONCE, consumerReject);
        requestNextPendingJobDocument();
    }

    /**
     * Request the job description of the next available job for this Iot Thing.
     * It publishes on the $aws/things/{thingName}/jobs/$next/get topic.
     *
     * @throws ExecutionException   if publishing to the topic fails
     * @throws InterruptedException if the thread gets interrupted
     */
    public void requestNextPendingJobDocument() {
        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = thingName;
        describeJobExecutionRequest.jobId = "$next";
        describeJobExecutionRequest.includeJobDocument = true;
        iotJobsClient.PublishDescribeJobExecution(describeJobExecutionRequest, QualityOfService.AT_LEAST_ONCE);
    }

    /**
     * Subscribe to $aws/things/{thingName}/jobs/notify topic.
     *
     * @param eventHandler The handler which run when an event is received
     * @throws ExecutionException   When subscribe failed with an exception
     * @throws InterruptedException When this thread was interrupted
     */
    public void subscribeToEventNotifications(Consumer<JobExecutionsChangedEvent> eventHandler)
            throws ExecutionException, InterruptedException {
        JobExecutionsChangedSubscriptionRequest request = new JobExecutionsChangedSubscriptionRequest();
        request.thingName = thingName;
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToJobExecutionsChangedEvents(request, QualityOfService.AT_LEAST_ONCE, eventHandler);
        subscribed.get();
    }

}
