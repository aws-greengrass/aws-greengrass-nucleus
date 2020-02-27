/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.io.EventLoopGroup;
import software.amazon.awssdk.crt.io.HostResolver;
import software.amazon.awssdk.crt.mqtt.MqttClientConnection;
import software.amazon.awssdk.crt.mqtt.MqttClientConnectionEvents;
import software.amazon.awssdk.crt.mqtt.QualityOfService;
import software.amazon.awssdk.iot.AwsIotMqttConnectionBuilder;
import software.amazon.awssdk.iot.iotjobs.IotJobsClient;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

@NoArgsConstructor
public class IotJobsHelper {

    private static String UPDATE_SPECIFIC_JOB_ACCEPTED_TOPIC = "$aws/things/{thingName}/jobs/{jobId}/update/accepted";
    private static String UPDATE_SPECIFIC_JOB_REJECTED_TOPIC = "$aws/things/{thingName}/jobs/{jobId}/update/rejected";

    //IotJobsHelper is not in Context, so initializing a new one here. It will be added to context in later iterations
    private static Logger logger = LogManager.getLogger(IotJobsHelper.class);

    private String thingName;
    private IotJobsClient iotJobsClient;
    private MqttClientConnection connection;

    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            if (errorCode != 0) {
                logger.error("Connection interrupted: " + errorCode + ": " + CRT.awsErrorString(errorCode));
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.info("Connection resumed: " + (sessionPresent ? "existing session" : "clean session"));
        }
    };

    /**
     * Constructor.
     *
     * @param thingName       Iot thing name
     * @param clientEndpoint  Custom endpoint for the aws account
     * @param certificateFile File path for the Iot Thing certificate
     * @param privateKeyFile  File path for the private key for Iot thing
     * @param rootCaPath      File path for the root CA
     */
    public IotJobsHelper(String thingName, String clientEndpoint, String certificateFile, String privateKeyFile,
                         String rootCaPath, String clientId) {
        this.thingName = thingName;
        setupConnectionToAWSIot(certificateFile, privateKeyFile, clientEndpoint, rootCaPath, clientId);
    }

    /**
     * Sets up the IotJobsClient client over Mqtt.
     */
    private void setupConnectionToAWSIot(String certificateFile, String privateKeyFile, String clientEndpoint,
                                         String rootCaPath, String clientId) {
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
             HostResolver resolver = new HostResolver(eventLoopGroup);
             ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
             AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder
                     .newMtlsBuilderFromPath(certificateFile, privateKeyFile)) {

            builder.withCertificateAuthorityFromPath(null, rootCaPath).withEndpoint(clientEndpoint)
                    .withClientId(clientId).withCleanSession(true).withBootstrap(clientBootstrap)
                    .withConnectionEventCallbacks(callbacks);

            connection = builder.build();
            builder.withClientId(UUID.randomUUID().toString());
            iotJobsClient = new IotJobsClient(connection);
            connection.connect();
        }
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
     * @param jobId  The jobId to be updated
     * @param status The {@link JobStatus} to which to update
     * @return
     */
    public void updateJobStatus(String jobId, JobStatus status, HashMap<String, String> statusDetailsMap)
            throws ExecutionException, InterruptedException {

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
        updateJobRequest.status = status;
        updateJobRequest.statusDetails = statusDetailsMap;
        updateJobRequest.thingName = thingName;
        iotJobsClient.PublishUpdateJobExecution(updateJobRequest, QualityOfService.AT_LEAST_ONCE);
    }

    /**
     * Subscribes to the describe job topic to get the job details for the next available pending job
     * It also publishes a message to get the next pending job.
     * It returns an empty message if nothing is available
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
        getNextPendingJob();
    }

    /**
     * Get the job description of the next available job for this Iot Thing.
     *
     * @throws ExecutionException   {@link ExecutionException}
     * @throws InterruptedException {@link InterruptedException}
     */
    public void getNextPendingJob() throws ExecutionException, InterruptedException {
        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = thingName;
        describeJobExecutionRequest.jobId = "$next";
        describeJobExecutionRequest.includeJobDocument = true;
        CompletableFuture<Integer> published =
                iotJobsClient.PublishDescribeJobExecution(describeJobExecutionRequest, QualityOfService.AT_LEAST_ONCE);
        published.get();
    }
}
