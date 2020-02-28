/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 */

package com.aws.iot.evergreen.deployment;

import com.amazonaws.services.iot.client.AWSIotException;
import com.amazonaws.services.iot.client.AWSIotMessage;
import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.dependency.State;
import com.aws.iot.evergreen.deployment.model.AwsIotJobsMqttMessage;
import com.aws.iot.evergreen.deployment.model.DeploymentPacket;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.kernel.Kernel;
import com.aws.iot.evergreen.packagemanager.PackageManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.inject.Inject;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {
    private static final ObjectMapper OBJECT_MAPPER =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false)
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();
    private static final String NOTIFY_TOPIC = "$aws/things/{thingName}/jobs/notify";

    @Inject
    private IotJobsHelper iotJobsHelper;
    private MqttHelper mqttHelper;
    private final AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private boolean errored;
    //Thread safe?
    private final ExecutorService executorService = context.get(ExecutorService.class);
    private Future<Boolean> currentProcessStatus = null;
    private String currentJobId;
    private DeploymentPacket currentDeploymentPacket;

    private final Consumer<AWSIotMessage> awsIotNotifyMessageHandler = awsIotMessage -> {
        /*
         * This message is received when either of these things happen
         * 1. Last job completed (successful/failed)
         * 2. A new job was queued
         * 3. A job was cancelled
         * This message receives the list of Queued and InProgress jobs at the time of this message
         */
        logger.info("Received mqtt notify message with payload {}", awsIotMessage.getStringPayload());

        AwsIotJobsMqttMessage jobsMqttMessage;
        try {
            jobsMqttMessage = OBJECT_MAPPER.readValue(awsIotMessage.getStringPayload(), AwsIotJobsMqttMessage.class);
        } catch (JsonProcessingException ex) {
            logger.error("Incorrectly formatted message received from AWS Iot", ex);
            return;
        }

        try {
            //TODO: Check that if there is a current job runnign by the device then thats
            // coming in the inProgress list. If its not there then it will be an indication that
            // it was cancelled.
            if (!jobsMqttMessage.getJobs().getQueued().isEmpty()) {
                iotJobsHelper.getNextPendingJob();
            }
        } catch (ExecutionException | InterruptedException ex) {
            //TODO: DA should continue listening for other messages if error in one message
            logger.error("Caught exception while handling Mqtt message", ex);
            errored = true;
            reportState(State.ERRORED);
        }
    };

    private final Consumer<DescribeJobExecutionResponse> describeJobExecutionResponseConsumer = response -> {
        try {
            if (response.execution == null) {
                return;
            }

            JobExecutionData jobExecutionData = response.execution;
            currentJobId = jobExecutionData.jobId;
            logger.atInfo().log("Received job description for job id : {} and status {}", currentJobId,
                    jobExecutionData.status);
            if (jobExecutionData.status == JobStatus.IN_PROGRESS) {
                //TODO: Check the currently runnign process,
                // if it is same as this jobId then do nothing. If not then there is something wrong
                return;
            } else if (jobExecutionData.status == JobStatus.QUEUED) {
                //There should be no job runnign at this point of time
                iotJobsHelper.updateJobStatus(currentJobId, JobStatus.IN_PROGRESS, null);

                logger.info("Updated the status of JobsId {} to {}", currentJobId, JobStatus.IN_PROGRESS);
                currentDeploymentPacket = DeploymentPacket.builder().jobDocument(response.execution.jobDocument)
                        .proposedPackagesFromDeployment(new HashSet<>()).packagesToDeploy(new HashSet<>())
                        .removedTopLevelPackageNames(new HashSet<>()).build();
                //Starting the job processing in another thread
                currentProcessStatus = executorService
                        .submit(new DeploymentProcess(currentDeploymentPacket, OBJECT_MAPPER, context.get(Kernel.class),
                                context.get(PackageManager.class)));
                logger.atInfo().log("Submitted the job with jobId {}", jobExecutionData.jobId);
            }

        } catch (InterruptedException | ExecutionException e) {
            logger.error("Caught exception in callback to handle describe job response {}", e);
            //TODO:Exception handling in callbacks
        }
    };

    private void updateJobAsSucceded(String jobId, DeploymentPacket currentDeploymentPacket)
            throws ExecutionException, InterruptedException {
        //TODO: Fill in status details from the deployment packet
        iotJobsHelper.updateJobStatus(jobId, JobStatus.SUCCEEDED, null);
    }

    private void updateJobAsFailed(String jobId, DeploymentPacket deploymentPacket)
            throws ExecutionException, InterruptedException {
        //TODO: Fill in status details from the deployment packet
        iotJobsHelper.updateJobStatus(jobId, JobStatus.FAILED, null);
    }

    public DeploymentService(Topics topics) {
        super(topics);
    }


    @Override
    public void startup() {
        // Reset shutdown signal since we're trying to startup here
        this.receivedShutdown.set(false);

        logger.info("Starting up the Deployment Service");
        String thingName = getStringParameterFromConfig("thingName");
        if (thingName.isEmpty()) {
            logger.info("There is no thingName assigned to this device. Cannot communicate with cloud."
                    + " Finishing deployment service");
            reportState(State.FINISHED);
            return;
        }

        try {
            initialize(thingName);
            reportState(State.RUNNING);

            // TODO: Move to one SDK.
            // Subscribe to change event does not work well with jobs sdk, so using iot sdk to subscribe to notify topic
            // The Jobs SDK is flaky with its Future responses. When SubscribeToJobExecutionsChangedEvents
            // call is used in Jobs SDK, then PublishDescribeJobExecution is not able to publish the message.
            // Tried using different client connections for different subscriptions
            String topic = NOTIFY_TOPIC.replace("{thingName}", thingName);
            mqttHelper.subscribe(topic, awsIotNotifyMessageHandler);

            iotJobsHelper.subscribeToGetNextJobDecription(describeJobExecutionResponseConsumer, rejectedError -> {
                logger.error("Job subscription got rejected", rejectedError);
            });
        } catch (ExecutionException | InterruptedException | AWSIotException ex) {
            logger.error("Caught exception in subscribing to topics", ex);
            errored = true;
            reportState(State.ERRORED);
        }
        logger.info("Running deployment service");
        while (!receivedShutdown.get() && !errored) {
            try {
                if (currentProcessStatus != null) {
                    logger.info("Getting the status of the current process");
                    Boolean deploymentStatus = currentProcessStatus.get();
                    if (deploymentStatus) {
                        updateJobAsSucceded(currentJobId, currentDeploymentPacket);
                    } else {
                        updateJobAsFailed(currentJobId, currentDeploymentPacket);
                    }
                    currentProcessStatus = null;
                    currentDeploymentPacket = null;
                }
                Thread.sleep(DEPLOYMENT_POLLING_FREQUENCY);
            } catch (InterruptedException ex) {
                logger.atError().setCause(ex).log("Exception encountered while sleeping in DA");
                errored = true;
                reportState(State.ERRORED);
            } catch (ExecutionException e) {
                logger.atError().setCause(e).addKeyValue("jobId", currentJobId)
                        .log("Caught exception while getting the status of the Job");
                //Do not stop the thread as it should go on to process other incoming messages
            }
        }
    }


    @Override
    public void shutdown() {
        receivedShutdown.set(true);
        iotJobsHelper.closeConnection();
    }

    private void initialize(String thingName) throws AWSIotException {
        String envHome = System.getenv("HOME");
        String privateKeyPath = envHome + getStringParameterFromConfig("privateKeyPath");
        String certificateFilePath = envHome + getStringParameterFromConfig("certificateFilePath");
        String rootCAPath = envHome + getStringParameterFromConfig("rootCaPath");
        String clientEndpoint = getStringParameterFromConfig("mqttClientEndpoint");

        mqttHelper = new MqttHelper(clientEndpoint, UUID.randomUUID().toString(), certificateFilePath, privateKeyPath);
        iotJobsHelper = new IotJobsHelper(thingName, clientEndpoint, certificateFilePath, privateKeyPath, rootCAPath,
                UUID.randomUUID().toString());
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Topic childTopic = config.findLeafChild(parameterName);
        if (childTopic != null) {
            paramValue = childTopic.getOnce().toString();
        }
        return paramValue;
    }
}
