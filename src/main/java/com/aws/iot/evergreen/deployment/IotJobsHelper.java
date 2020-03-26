/* Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import javafx.util.Duration;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@NoArgsConstructor
public class IotJobsHelper {

    //The time within which device expects an acknowledgement from Iot cloud after publishing an MQTT message
    //This value needs to be revisited and set to more realistic numbers
    private static final long TIMEOUT_FOR_RESPONSE_FROM_IOT_CLOUD_SECONDS = (long) Duration.minutes(5).toSeconds();

    //The time it takes for device to publish a message
    //This value needs to be revisited and set to more realistic numbers
    private static final long TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS = (long) Duration.minutes(1).toSeconds();

    public static final String UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC =
            "$aws/things/{thingName}/jobs/{jobId}/update" + "/accepted";
    public static final String UPDATE_SPECIFIC_JOB_REJECTED_TOPIC =
            "$aws/things/{thingName}/jobs/{jobId}/update" + "/rejected";

    private static Logger logger = LogManager.getLogger(IotJobsHelper.class);

    private String thingName;
    private IotJobsClient iotJobsClient;
    private MqttClientConnection connection;


    /**
     * Connects to AWS Iot Cloud.
     *
     * @throws ExecutionException   if connecting fails
     * @throws InterruptedException if interrupted while connecting
     * @throws TimeoutException     if the operation does not complete within the given time
     */
    public void connectToAwsIot() throws ExecutionException, InterruptedException, TimeoutException {
        connection.connect().get(TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS, TimeUnit.SECONDS);
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
     * @throws ExecutionException   if update fails
     * @throws InterruptedException if the thread gets interrupted
     * @throws TimeoutException     if the operation does not complete within the given time
     */
<<<<<<< HEAD
<<<<<<< HEAD
    @SuppressWarnings("PMD.LooseCoupling")
    public void updateJobStatus(String jobId, JobStatus status, HashMap<String, String> statusDetailsMap) {
=======
    public CompletableFuture<Integer> updateJobStatus(String jobId, JobStatus status,
                                                      Long executionNumber,
                                                      HashMap<String, String> statusDetailsMap) {
<<<<<<< HEAD
<<<<<<< HEAD
>>>>>>> Persisting deployment status during MQTT connection breakage
=======
        logger.atInfo().kv("JobId", jobId).kv("Status", status).log("Updating job status");
>>>>>>> Adding execution number to the job update call
=======
=======
    public void updateJobStatus(String jobId, JobStatus status, Long executionNumber,
                                HashMap<String, String> statusDetailsMap)
            throws ExecutionException, InterruptedException, TimeoutException {
>>>>>>> Refactoring Deployment Service
        logger.atDebug().kv("JobId", jobId).kv("Status", status).log("Updating job status");
>>>>>>> Updating the status of deployments in the order of their completion. Refactoring DeploymentTask to parse the job document
        UpdateJobExecutionSubscriptionRequest subscriptionRequest = new UpdateJobExecutionSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        subscriptionRequest.jobId = jobId;
        CompletableFuture<Void> gotResponse = new CompletableFuture<>();
        iotJobsClient.SubscribeToUpdateJobExecutionAccepted(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.atInfo().kv("JobId", jobId).kv("Status", status).log("Job status updated accepted");
                    String topicForJobId = UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC.replace("{thingName}", thingName)
                            .replace("{jobId}", jobId);
                    connection.unsubscribe(topicForJobId);
                    gotResponse.complete(null);
                });

        iotJobsClient.SubscribeToUpdateJobExecutionRejected(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.atWarn().kv("JobId", jobId).kv("Status", status).log("Job status updated rejected");
                    String topicForJobId = UPDATE_SPECIFIC_JOB_REJECTED_TOPIC.replace("{thingName}", thingName)
                            .replace("{jobId}", jobId);
                    connection.unsubscribe(topicForJobId);
                    //Can this be due to duplicate messages being sent for the job?
                    gotResponse.completeExceptionally(new Exception(response.message));
                });

        UpdateJobExecutionRequest updateJobRequest = new UpdateJobExecutionRequest();
        updateJobRequest.jobId = jobId;
        updateJobRequest.executionNumber = executionNumber;
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
    public void subscribeToGetNextJobDescription(Consumer<DescribeJobExecutionResponse> consumerAccept,
                                                 Consumer<RejectedError> consumerReject)
            throws ExecutionException, InterruptedException, TimeoutException {
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
    public void subscribeToEventNotifications(Consumer<JobExecutionsChangedEvent> eventHandler)
            throws ExecutionException, InterruptedException, TimeoutException {
        JobExecutionsChangedSubscriptionRequest request = new JobExecutionsChangedSubscriptionRequest();
        request.thingName = thingName;
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToJobExecutionsChangedEvents(request, QualityOfService.AT_LEAST_ONCE, eventHandler);
        subscribed.get(TIMEOUT_FOR_IOT_JOBS_OPERATIONS_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Request the job description of the next available job for this Iot Thing.
     * It publishes on the $aws/things/{thingName}/jobs/$next/get topic.
     */
    public void requestNextPendingJobDocument() {
        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = thingName;
        describeJobExecutionRequest.jobId = "$next";
        describeJobExecutionRequest.includeJobDocument = true;
        //This method is specifically called from an async event notification handler. Async handler cannot block on
        // this future as that will freeze the MQTT connection.
        iotJobsClient.PublishDescribeJobExecution(describeJobExecutionRequest, QualityOfService.AT_LEAST_ONCE);
    }
}
