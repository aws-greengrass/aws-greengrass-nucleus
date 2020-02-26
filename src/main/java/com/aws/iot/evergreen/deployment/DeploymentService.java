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
import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.iot.iotjobs.model.DescribeJobExecutionResponse;
import software.amazon.awssdk.iot.iotjobs.model.JobExecutionData;
import software.amazon.awssdk.iot.iotjobs.model.JobStatus;
import software.amazon.awssdk.iot.iotjobs.model.RejectedError;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@ImplementsService(name = "DeploymentService", autostart = true)
public class DeploymentService extends EvergreenService {

    private static Long DEPLOYMENT_POLLING_FREQUENCY = Duration.ofSeconds(30).toMillis();
    private static String NOTIFY_TOPIC = "$aws/things/{thingName}/jobs/notify";

    private static Logger logger = LogManager.getLogger(DeploymentService.class);

    private IotJobsHelper iotJobsHelper;
    private MqttHelper mqttHelper;
    private AtomicBoolean receivedShutdown = new AtomicBoolean(false);
    private boolean errored;

    private Consumer<AWSIotMessage> awsIotNotifyMessageHandler = new Consumer<AWSIotMessage>() {
        @Override
        public void accept(AWSIotMessage awsIotMessage) {
            logger.info("Received mqtt notify message");
            logger.info("Payload: " + awsIotMessage.getStringPayload());
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            AwsIotJobsMqttMessage jobsMqttMessage;
            try {
                jobsMqttMessage = objectMapper.readValue(awsIotMessage.getStringPayload(), AwsIotJobsMqttMessage.class);
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
        }
    };

    private Consumer<DescribeJobExecutionResponse> describeJobExecutionResponseConsumer =
            new Consumer<DescribeJobExecutionResponse>() {
                @Override
                public void accept(DescribeJobExecutionResponse response) {
                    {
                        if (response.execution == null) {
                            return;
                        }
                        logger.info("Describe Job: " + response.execution.jobId + " version: "
                                + response.execution.versionNumber);
                        if (response.execution.jobDocument != null) {
                            response.execution.jobDocument.forEach((key, value) -> {
                                logger.info("  " + key + ": " + value);
                            });
                        }
                        JobExecutionData jobExecutionData = response.execution;
                        String jobId = jobExecutionData.jobId;
                        logger.info("JobId is " + jobId);
                        Map<String, Object> jobDocument = jobExecutionData.jobDocument;
                        HashMap<String, String> statusDetails = new HashMap<String, String>();
                        try {
                            if (jobDocument == null) {
                                statusDetails.put("JobDocument", "Empty");
                                iotJobsHelper.updateJobStatus(jobId, JobStatus.FAILED, statusDetails);
                                return;
                            }
                            jobDocument.forEach((key, value) -> {
                                logger.info(key, ":", value);
                            });
                            logger.info("JOb status is " + jobExecutionData.status);
                            if (jobExecutionData.status == JobStatus.QUEUED) {
                                logger.info("Updating the status of JobsId " + jobId + "to in progress");
                                iotJobsHelper.updateJobStatus(jobId, JobStatus.IN_PROGRESS, null);
                                logger.info("Updated the status of JobsId " + jobId + "to in progress");
                                //TODO: Trigger deployment process
                            }
                            //TODO:Check that if job Id is in progress and take appropriate action.
                            // We expect only one JobId to be in progress at a time

                            logger.info("Updating the status of JobId " + jobId + "to in completed");
                            iotJobsHelper.updateJobStatus(jobId, JobStatus.SUCCEEDED, null);
                            logger.info("Updated the status of JobId" + jobId + "to in completed");
                        } catch (ExecutionException | InterruptedException ex) {
                            //TODO: If error in one job then DA should continue listening for other jobs
                            logger.error("Caught exception while doing a deployment", ex);
                            errored = true;
                            reportState(State.ERRORED);
                        }
                    }
                }
            };

    public DeploymentService(Topics topics) {
        super(topics);
        iotJobsHelper = new IotJobsHelper();
    }

    @Override
    public void startup() {
        logger.info("Starting up the Deployment Service");
        String thingName = getStringParameterFromConfig("thingName");
        if (thingName.isEmpty()) {
            logger.info("There is no thingName assigned to this device. Cannot communicate with cloud."
                    + " Finishing deployment service");
            reportState(State.FINISHED);
            return;
        }
        String envHome = System.getenv("HOME");
        String privateKeyPath = envHome + getStringParameterFromConfig("privateKeyPath");
        String certificateFilePath = envHome + getStringParameterFromConfig("certificateFilePath");
        String rootCAPath = envHome + getStringParameterFromConfig("rootCaPath");
        String clientEndpoint = getStringParameterFromConfig("mqttClientEndpoint");

        mqttHelper = new MqttHelper(clientEndpoint, UUID.randomUUID().toString(), certificateFilePath, privateKeyPath);
        iotJobsHelper = new IotJobsHelper(thingName, clientEndpoint, certificateFilePath, privateKeyPath, rootCAPath,
                UUID.randomUUID().toString());
        reportState(State.RUNNING);

        try {
            // TODO: Move to one SDK.
            // Subscribe to change event does not work well with jobs sdk, so using iot sdk to subscribe to notify topic
            // The Jobs SDK is flaky with its Future reponses. When SubscribeToJobExecutionsChangedEvents
            // call is used in Jobs SDK, then PublishDescribeJobExecution is not able to publish the message.
            // Tried using different client connections for different subscriptions
            String topic = NOTIFY_TOPIC.replace("{thingName}", thingName);
            mqttHelper.subscribe(topic, awsIotNotifyMessageHandler);

            iotJobsHelper.subscribeToGetNextJobDecription(describeJobExecutionResponseConsumer,
                    new Consumer<RejectedError>() {
                        @Override
                        public void accept(RejectedError rejectedError) {
                            logger.error("Job subscription got rejected");
                            logger.error(rejectedError.message);
                        }
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
                logger.error("Exception encountered: " + ex.toString());
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
        logger.info("Returning value: " + paramValue);
        return paramValue;
    }
}
