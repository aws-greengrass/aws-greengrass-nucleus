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
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
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
    private AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private boolean errored;

    private Consumer<AWSIotMessage> awsIotNotifyMessageHandler = awsIotMessage -> {
        logger.info("Received mqtt notify message");
        logger.info("Payload: {}", awsIotMessage.getStringPayload());
        AwsIotJobsMqttMessage jobsMqttMessage;
        try {
            jobsMqttMessage = OBJECT_MAPPER.readValue(awsIotMessage.getStringPayload(), AwsIotJobsMqttMessage.class);
        } catch (JsonProcessingException ex) {
            logger.error("Incorrectly formatted message received from AWS Iot", ex);
            return;
        }

        try {
            if (!jobsMqttMessage.getJobs().getQueued().isEmpty()) {
                iotJobsHelper.getNextPendingJob();
            }
        } catch (ExecutionException | InterruptedException ex) {
            //TODO: DA should continue listening for other messages if error in one message
            logger.error("Caught exception while handling Mqtt message ", ex);
            errored = true;
            reportState(State.ERRORED);
        }
    };

    private Consumer<DescribeJobExecutionResponse> describeJobExecutionResponseConsumer = response -> {
        if (response.execution == null) {
            return;
        }
        logger.info("Describe Job: {} version: {}", response.execution.jobId, response.execution.versionNumber);
        JobExecutionData jobExecutionData = response.execution;
        String jobId = jobExecutionData.jobId;
        Map<String, Object> jobDocument = jobExecutionData.jobDocument;
        HashMap<String, String> statusDetails = new HashMap<>();
        try {
            if (jobDocument == null) {
                statusDetails.put("JobDocument", "Empty");
                iotJobsHelper.updateJobStatus(jobId, JobStatus.FAILED, statusDetails);
                return;
            }
            logger.atInfo().setEventType("got-deployment-job-doc").addKeyValue("jobDocument", jobDocument).log();
            logger.info("Job status is {}", jobExecutionData.status);
            if (jobExecutionData.status == JobStatus.QUEUED) {
                iotJobsHelper.updateJobStatus(jobId, JobStatus.IN_PROGRESS, null);
                logger.debug("Updated the status of JobsId {} to in progress", jobId);
                //TODO: Trigger deployment process
            }
            //TODO:Check that if job Id is in progress and take appropriate action.
            // We expect only one JobId to be in progress at a time

            iotJobsHelper.updateJobStatus(jobId, JobStatus.SUCCEEDED, null);
            logger.debug("Updated the status of JobId {} to in completed", jobId);
        } catch (ExecutionException | InterruptedException ex) {
            //TODO: If error in one job then DA should continue listening for other jobs
            logger.error("Caught exception while doing a deployment", ex);
            errored = true;
            reportState(State.ERRORED);
        }
    };

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
        String privateKeyPath = getStringParameterFromConfig("privateKeyPath");
        String certificateFilePath = getStringParameterFromConfig("certificateFilePath");
        String rootCAPath = getStringParameterFromConfig("rootCaPath");
        String clientEndpoint = getStringParameterFromConfig("mqttClientEndpoint");

        try {
            MqttHelper mqttHelper =
                    new MqttHelper(clientEndpoint, UUID.randomUUID().toString(), certificateFilePath, privateKeyPath);
            iotJobsHelper =
                    new IotJobsHelper(thingName, clientEndpoint, certificateFilePath, privateKeyPath, rootCAPath,
                            UUID.randomUUID().toString());
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
                Thread.sleep(DEPLOYMENT_POLLING_FREQUENCY);
            } catch (InterruptedException ex) {
                logger.atError().setCause(ex).log("Exception encountered while sleeping in DA");
                errored = true;
                reportState(State.ERRORED);
            }
        }
    }

    @Override
    public void shutdown() {
        receivedShutdown.set(true);
        iotJobsHelper.closeConnection();
    }

    private String getStringParameterFromConfig(String parameterName) {
        String paramValue = "";
        Topic childTopic = config.findLeafChild(parameterName);
        if (childTopic != null) {
            paramValue = childTopic.getOnce().toString();
        }
        logger.info("Returning value: {}", paramValue);
        return paramValue;
    }
}
