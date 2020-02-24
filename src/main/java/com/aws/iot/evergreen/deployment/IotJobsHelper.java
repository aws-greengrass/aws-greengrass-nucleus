/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.util.Log;
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
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.GetPendingJobExecutionsRequest;
import software.amazon.awssdk.iot.iotjobs.model.GetPendingJobExecutionsSubscriptionRequest;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionSummary;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionRequest;
import software.amazon.awssdk.iot.iotjobs.model.UpdateJobExecutionSubscriptionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import javax.inject.Inject;

@NoArgsConstructor
public class IotJobsHelper {

    @Inject
    private static Log logger;

    private String clientId = UUID.randomUUID().toString(); // Use unique client IDs for concurrent connections.
    private String clientEndpoint;
    private String certificateFile;
    private String privateKeyFile;
    private String rootCaPath;
    private String thingName;
    private IotJobsClient iotJobsClient;
    private MqttClientConnection connection;

    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            if (errorCode != 0) {
                logger.log(Log.Level.Error,
                        "Connection interrupted: " + errorCode + ": " + CRT.awsErrorString(errorCode));
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.log(Log.Level.Note,
                    "Connection resumed: " + (sessionPresent ? "existing session" : "clean session"));
        }
    };


    /**
     * Sets up the device context.
     *
     * @param thingName       The IotThingName
     * @param certificateFile Path for the X.509 certificate which device will use to connect to Iot cloud
     * @param privateKeyFile  Path for the private key used by device to connect to Iot cloud
     * @param rootCaPath      Path for the root CA certificate
     * @param clientEndpoint  The Mqtt endpoint for this AWS customer account
     */
    public void setDeviceContext(String thingName, String certificateFile, String privateKeyFile, String rootCaPath,
                                 String clientEndpoint) {
        this.thingName = thingName;
        this.certificateFile = certificateFile;
        this.privateKeyFile = privateKeyFile;
        this.rootCaPath = rootCaPath;
        this.clientEndpoint = clientEndpoint;
    }

    /**
     * Sets up the IotJobsClient client over Mqtt.
     */
    public void setupConnectionToAWSIot() {
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
             HostResolver resolver = new HostResolver(eventLoopGroup);
             ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
             AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder
                     .newMtlsBuilderFromPath(certificateFile, privateKeyFile)) {

            builder.withCertificateAuthorityFromPath(null, rootCaPath).withEndpoint(clientEndpoint)
                    .withClientId(clientId).withCleanSession(true).withBootstrap(clientBootstrap)
                    .withConnectionEventCallbacks(callbacks);

            connection = builder.build();
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
     * Updates the status of an Iot Job with given JobId to a given status.
     *
     * @param jobId  The jobId to be updated
     * @param status The {@link JobStatus} to which to update
     * @return
     */
    public CompletableFuture<Boolean> updateJobStatus(String jobId, JobStatus status,
                                                      HashMap<String, String> statusDetailsMap)
            throws ExecutionException, InterruptedException {
        CompletableFuture<Boolean> responseFuture = new CompletableFuture<>();

        UpdateJobExecutionSubscriptionRequest subscriptionRequest = new UpdateJobExecutionSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        subscriptionRequest.jobId = jobId;
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToUpdateJobExecutionAccepted(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                        (response) -> {
                            logger.log(Log.Level.Note, "Marked job " + jobId + "as " + status);
                            responseFuture.complete(Boolean.TRUE);
                        });
        subscribed.get();

        subscribed = iotJobsClient
                .SubscribeToUpdateJobExecutionRejected(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                        (response) -> {
                            logger.log(Log.Level.Error, "Job " + jobId + " not updated as " + status);
                            responseFuture.completeExceptionally(new RuntimeException(response.message));
                        });

        subscribed.get();
        UpdateJobExecutionRequest updateJobRequest = new UpdateJobExecutionRequest();
        updateJobRequest.jobId = jobId;
        updateJobRequest.status = status;
        updateJobRequest.statusDetails = statusDetailsMap;
        updateJobRequest.thingName = thingName;
        CompletableFuture<Integer> published =
                iotJobsClient.PublishUpdateJobExecution(updateJobRequest, QualityOfService.AT_LEAST_ONCE);
        published.get();
        return responseFuture;
    }

    /**
     * Get the list of all queued jobs.
     *
     * @return List of Job Ids
     */
    public CompletableFuture<List<String>> getAllQueuedJobs() throws ExecutionException, InterruptedException {
        CompletableFuture<List<String>> responseFuture = new CompletableFuture<>();
        List<String> queuedJobs = new ArrayList<>();
        GetPendingJobExecutionsSubscriptionRequest subscriptionRequest =
                new GetPendingJobExecutionsSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToGetPendingJobExecutionsAccepted(subscriptionRequest, QualityOfService.AT_LEAST_ONCE,
                        (response) -> {
                            logger.log(Log.Level.Note,
                                    "Queued Jobs: " + (response.queuedJobs.size() + response.inProgressJobs.size() == 0
                                            ? "none" : ""));

                            for (JobExecutionSummary job : response.queuedJobs) {
                                queuedJobs.add(job.jobId);
                                logger.log(Log.Level.Note, " " + job.jobId + " @ " + job.lastUpdatedAt.toString());
                            }
                            responseFuture.complete(queuedJobs);
                        });
        subscribed.get();


        GetPendingJobExecutionsRequest publishRequest = new GetPendingJobExecutionsRequest();
        publishRequest.thingName = thingName;
        CompletableFuture<Integer> published =
                iotJobsClient.PublishGetPendingJobExecutions(publishRequest, QualityOfService.AT_LEAST_ONCE);
        published.get();
        return responseFuture;
    }

    /**
     * Get the job document for the given Job Id.
     *
     * @param jobId The Job Id
     * @return Job document
     */
    public CompletableFuture<Map<String, Object>> getJobDetails(String jobId)
            throws ExecutionException, InterruptedException {
        CompletableFuture<Map<String, Object>> responseFuture = new CompletableFuture<>();

        DescribeJobExecutionSubscriptionRequest describeJobExecutionSubscriptionRequest =
                new DescribeJobExecutionSubscriptionRequest();
        describeJobExecutionSubscriptionRequest.thingName = thingName;
        describeJobExecutionSubscriptionRequest.jobId = jobId;
        CompletableFuture<Integer> subscribed = iotJobsClient
                .SubscribeToDescribeJobExecutionAccepted(describeJobExecutionSubscriptionRequest,
                        QualityOfService.AT_LEAST_ONCE, (response) -> {
                            logger.log(Log.Level.Note, "Describe Job: " + response.execution.jobId + " version: "
                                    + response.execution.versionNumber);
                            if (response.execution.jobDocument != null) {
                                response.execution.jobDocument.forEach((key, value) -> {
                                    logger.log(Log.Level.Note, "  " + key + ": " + value);
                                });
                            }
                            responseFuture.complete(response.execution.jobDocument);
                        });

        subscribed.get();


        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = thingName;
        describeJobExecutionRequest.jobId = jobId;
        describeJobExecutionRequest.includeJobDocument = true;
        describeJobExecutionRequest.executionNumber = 1L;
        CompletableFuture<Integer> published =
                iotJobsClient.PublishDescribeJobExecution(describeJobExecutionRequest, QualityOfService.AT_LEAST_ONCE);
        published.get();
        return responseFuture;
    }
}
