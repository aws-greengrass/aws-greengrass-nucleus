package com.aws.iot.evergreen.deployment;

import com.aws.iot.evergreen.util.Log;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.CrtRuntimeException;
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

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@NoArgsConstructor
public class DeploymentAgent {

    @Inject
    private static Log logger;

    private String clientId = UUID.randomUUID().toString(); // Use unique client IDs for concurrent connections.
    private String clientEndpoint;
    private String certificateFile;
    private String privateKeyFile;
    private String rootCaPath;
    private String thingName;
    private IotJobsClient jobs;
    private MqttClientConnection connection;

    private MqttClientConnectionEvents callbacks = new MqttClientConnectionEvents() {
        @Override
        public void onConnectionInterrupted(int errorCode) {
            if (errorCode != 0) {
                logger.log(Log.Level.Error, "Connection interrupted: " + errorCode + ": " + CRT.awsErrorString(errorCode));
            }
        }

        @Override
        public void onConnectionResumed(boolean sessionPresent) {
            logger.log(Log.Level.Note, "Connection resumed: " + (sessionPresent ? "existing session" : "clean session"));
        }

    };


    /**
     * Sets up the device context
     *
     * @param thingName       The IotThingName
     * @param certificateFile Path for the X.509 certificate which device will use to connect to Iot cloud
     * @param privateKeyFile  Path for the private key used by device to connect to Iot cloud
     * @param rootCaPath      Path for the root CA certificate
     * @param clientEndpoint  The Mqtt endpoint for this AWS customer account
     */
    public void setDeviceContext(String thingName,
                                 String certificateFile,
                                 String privateKeyFile,
                                 String rootCaPath,
                                 String clientEndpoint) {
        this.thingName = thingName;
        this.certificateFile = certificateFile;
        this.privateKeyFile = privateKeyFile;
        this.rootCaPath = rootCaPath;
        this.clientEndpoint = clientEndpoint;
    }

    /**
     * Sets up the IotJobsClient client over Mqtt
     */
    public void setupConnectionToAWSIot() {
        try (EventLoopGroup eventLoopGroup = new EventLoopGroup(1);
             HostResolver resolver = new HostResolver(eventLoopGroup);
             ClientBootstrap clientBootstrap = new ClientBootstrap(eventLoopGroup, resolver);
             AwsIotMqttConnectionBuilder builder = AwsIotMqttConnectionBuilder.newMtlsBuilderFromPath(certificateFile, privateKeyFile)) {

            builder.withCertificateAuthorityFromPath(null, rootCaPath)
                    .withEndpoint(clientEndpoint)
                    .withClientId(clientId)
                    .withCleanSession(true)
                    .withBootstrap(clientBootstrap)
                    .withConnectionEventCallbacks(callbacks);

            connection = builder.build();
            jobs = new IotJobsClient(connection);
            connection.connect();
        } catch (CrtRuntimeException ex) {
            logger.log(Log.Level.Error, "Caught exception while establishing connection to AWS Iot. " +
                    "Exception encountered: " + ex.toString());
        }
    }

    /**
     * Closes the Mqtt connection
     */
    public void closeConnection() {
        connection.close();
    }

    /**
     * Connects to AWS Iot and retrieves the Iot Jobs which are queued for this device.
     * For each job, gets the job document, updates the jobs status to in progress and triggers the corresponding deployment
     */
    public void listenForDeployments() {

        try {
            List<String> queuedJobs = getAllQueuedJobs(jobs).get();

            for (String jobId : queuedJobs) {

                Map<String, Object> jobDocument = getJobDetails(jobs, jobId).get();

                //update the status of the job as in progress
                updateJobStatus(jobs, jobId, JobStatus.IN_PROGRESS);

            }
        } catch (CrtRuntimeException | InterruptedException | ExecutionException ex) {
            logger.log(Log.Level.Error, "Exception encountered: " + ex.toString());
        }

    }

    private CompletableFuture<Boolean> updateJobStatus(IotJobsClient jobs, String jobId, JobStatus status) {
        CompletableFuture<Boolean> responseFuture = new CompletableFuture<>();

        UpdateJobExecutionSubscriptionRequest subscriptionRequest = new UpdateJobExecutionSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        subscriptionRequest.jobId = jobId;
        CompletableFuture<Integer> subscribed = jobs.SubscribeToUpdateJobExecutionAccepted(
                subscriptionRequest,
                QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.log(Log.Level.Note, "Marked job " + jobId + "as " + status);
                    responseFuture.complete(Boolean.TRUE);
                });
        try {
            subscribed.get();
            logger.log(Log.Level.Note, "Subscribed to UpdateJobExecutionAccepted");
        } catch (Exception ex) {
            throw new RuntimeException("Failed to subscribe to UpdateJobExecutionAccepted", ex);
        }

        subscribed = jobs.SubscribeToUpdateJobExecutionRejected(
                subscriptionRequest,
                QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.log(Log.Level.Error, "Job " + jobId + " not updated as " + status);
                    responseFuture.complete(Boolean.FALSE);
                });

        try {
            subscribed.get();
            logger.log(Log.Level.Note, "Subscribed to UpdateJobExecutionRejected");
        } catch (Exception ex) {
            throw new RuntimeException("Failed to subscribe to UpdateJobExecutionRejected", ex);
        }

        UpdateJobExecutionRequest updateJobRequest = new UpdateJobExecutionRequest();
        updateJobRequest.jobId = jobId;
        updateJobRequest.status = status;
        updateJobRequest.thingName = thingName;
        CompletableFuture<Integer> published = jobs.PublishUpdateJobExecution(updateJobRequest, QualityOfService.AT_LEAST_ONCE);
        try {
            published.get();
            return responseFuture;
        } catch (Exception ex) {
            String errorMessage = String.format("Failed to publish the Job update for Job Id %s", jobId);
            throw new RuntimeException(errorMessage, ex);
        }
    }

    private CompletableFuture<List<String>> getAllQueuedJobs(IotJobsClient jobs) {
        CompletableFuture<List<String>> responseFuture = new CompletableFuture<>();
        List<String> queuedJobs = new ArrayList<>();
        GetPendingJobExecutionsSubscriptionRequest subscriptionRequest = new GetPendingJobExecutionsSubscriptionRequest();
        subscriptionRequest.thingName = thingName;
        CompletableFuture<Integer> subscribed = jobs.SubscribeToGetPendingJobExecutionsAccepted(
                subscriptionRequest,
                QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.log(Log.Level.Note, "Queued Jobs: " + (response.queuedJobs.size() + response.inProgressJobs.size() == 0 ? "none" : ""));

                    for (JobExecutionSummary job : response.queuedJobs) {
                        queuedJobs.add(job.jobId);
                        logger.log(Log.Level.Note, " " + job.jobId + " @ " + job.lastUpdatedAt.toString());
                    }
                    responseFuture.complete(queuedJobs);
                });
        try {
            subscribed.get();
            logger.log(Log.Level.Note, "Subscribed to GetPendingJobExecutionsAccepted");
        } catch (Exception ex) {
            throw new RuntimeException("Failed to subscribe to GetPendingJobExecutions", ex);
        }

        GetPendingJobExecutionsRequest publishRequest = new GetPendingJobExecutionsRequest();
        publishRequest.thingName = thingName;
        CompletableFuture<Integer> published = jobs.PublishGetPendingJobExecutions(
                publishRequest,
                QualityOfService.AT_LEAST_ONCE);
        try {
            int response = published.get();
            logger.log(Log.Level.Note, "Published to GetPendingJobExecutions with response " + response);
            return responseFuture;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to publish to GetPendingJobExecutions", ex);
        }
    }

    private CompletableFuture<Map<String, Object>> getJobDetails(IotJobsClient jobs, String jobId) {
        CompletableFuture<Map<String, Object>> responseFuture = new CompletableFuture<>();

        DescribeJobExecutionSubscriptionRequest describeJobExecutionSubscriptionRequest = new DescribeJobExecutionSubscriptionRequest();
        describeJobExecutionSubscriptionRequest.thingName = thingName;
        describeJobExecutionSubscriptionRequest.jobId = jobId;
        CompletableFuture<Integer> subscribed = jobs.SubscribeToDescribeJobExecutionAccepted(
                describeJobExecutionSubscriptionRequest,
                QualityOfService.AT_LEAST_ONCE,
                (response) -> {
                    logger.log(Log.Level.Note, "Describe Job: " + response.execution.jobId + " version: " + response.execution.versionNumber);
                    if (response.execution.jobDocument != null) {
                        response.execution.jobDocument.forEach((key, value) -> {
                            logger.log(Log.Level.Note, "  " + key + ": " + value);
                        });
                    }
                    responseFuture.complete(response.execution.jobDocument);
                });

        try {
            subscribed.get();
            logger.log(Log.Level.Note, "Subscribed to DescribeJobExecutionAccepted");
        } catch (Exception ex) {
            throw new RuntimeException("Failed to subscribe to DescribeJobExecutionAccepted", ex);
        }

        DescribeJobExecutionRequest describeJobExecutionRequest = new DescribeJobExecutionRequest();
        describeJobExecutionRequest.thingName = thingName;
        describeJobExecutionRequest.jobId = jobId;
        describeJobExecutionRequest.includeJobDocument = true;
        describeJobExecutionRequest.executionNumber = 1L;
        CompletableFuture<Integer> published = jobs.PublishDescribeJobExecution(describeJobExecutionRequest,
                QualityOfService.AT_LEAST_ONCE);
        try {
            published.get();
            return responseFuture;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to publish to DescribeJobExecution", ex);
        }

    }

}
