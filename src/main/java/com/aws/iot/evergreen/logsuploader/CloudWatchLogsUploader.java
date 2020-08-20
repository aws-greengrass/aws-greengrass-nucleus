/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader;

import com.aws.iot.evergreen.logging.api.Logger;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttempt;
import com.aws.iot.evergreen.util.CloudWatchClientFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DataAlreadyAcceptedException;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.InvalidSequenceTokenException;
import software.amazon.awssdk.services.cloudwatchlogs.model.LimitExceededException;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.cloudwatchlogs.model.ResourceNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javax.inject.Inject;

public class CloudWatchLogsUploader {
    private static final Logger logger = LogManager.getLogger(CloudWatchLogsUploader.class);
    private static Map<String, Consumer<CloudWatchAttempt>> listeners = new ConcurrentHashMap<>();
    // https://docs.aws.amazon.com/AmazonCloudWatch/latest/logs/cloudwatch_limits_cwl.html
    // TODO: Implement some back off.
    //private static final int MAX_TPS_SEC = 5;
    //private final List<CloudWatchAttempt> retryAttemptList = new ArrayList<>();
    private final CloudWatchLogsClient cloudWatchLogsClient;

    final Map<String, Map<String, String>> logGroupsToSequenceTokensMap = new ConcurrentHashMap<>();

    @Inject
    public CloudWatchLogsUploader(CloudWatchClientFactory cloudWatchClientFactory) {
        this.cloudWatchLogsClient = cloudWatchClientFactory.getCloudWatchLogsClient();
    }

    /**
     * Create Log group and log stream if necessary.
     *
     * @param attempt {@link CloudWatchAttempt}
     */
    public void upload(CloudWatchAttempt attempt) {
        attempt.getLogGroupsToLogStreamsMap().forEach((groupName, streamNameMap) -> {
            createNewLogGroup(groupName);
            streamNameMap.forEach((streamName, attemptLogInformation) -> {
                createNewLogSteam(groupName, streamName);
                boolean success = uploadLogs(groupName, streamName, attemptLogInformation.getLogEvents());
                if (success) {
                    List<String> streams = attempt.getLogStreamUploadedMap().getOrDefault(groupName, new ArrayList<>());
                    streams.add(streamName);
                    attempt.getLogStreamUploadedMap().put(groupName, streams);
                }
            });
        });
        listeners.values().forEach(consumer -> consumer.accept(attempt));
    }

    /**
     * Register a listener to get cloud watch attempt status.
     *
     * @param callback  The callback function to invoke.
     * @param name      The unique name for the service subscribing.
     */
    public void registerAttemptStatus(String name, Consumer<CloudWatchAttempt> callback) {
        listeners.putIfAbsent(name, callback);
    }

    /**
     * Unregister a listener to get cloud watch attempt status.
     *
     * @param name      The unique name for the service subscribing.
     */
    public void unRegisterAttemptStatus(String name) {
        listeners.remove(name);
    }

    /**
     * Uploads logs to CloudWatch.
     *
     * @param logEvents     The log events to upload to CloudWatch.
     * @param logGroupName  The log group name to upload the logs to.
     * @param logStreamName The log steam name to upload the logs to.
     */
    private boolean uploadLogs(String logGroupName, String logStreamName, List<InputLogEvent> logEvents) {
        logger.atDebug().log("Uploading {} logs to {}-{}", logEvents.size(), logGroupName, logStreamName);
        AtomicReference<String> sequenceToken = new AtomicReference<>();
        logGroupsToSequenceTokensMap.computeIfPresent(logGroupName, (groupName, streamToSequenceTokenMap) -> {
            streamToSequenceTokenMap.computeIfPresent(logStreamName, (streamName, savedSequenceToken) -> {
                sequenceToken.set(savedSequenceToken);
                return savedSequenceToken;
            });
            return streamToSequenceTokenMap;
        });
        PutLogEventsRequest request = PutLogEventsRequest.builder()
                .logEvents(logEvents)
                .logGroupName(logGroupName)
                .logStreamName(logStreamName)
                .sequenceToken(sequenceToken.get())
                .build();
        try {
            PutLogEventsResponse putLogEventsResponse = this.cloudWatchLogsClient.putLogEvents(request);
            addNextSequenceToken(logGroupName, logStreamName, putLogEventsResponse.nextSequenceToken());
            return true;
        } catch (InvalidSequenceTokenException e) {
            // Get correct token using describe
            logger.atError().cause(e).log("Get correct token.");
            addNextSequenceToken(logGroupName, logStreamName, getSequenceToken(logGroupName, logStreamName));
            // TODO: better do the retry mechanism? Maybe need to have a scheduled task to handle this.
            uploadLogs(logGroupName, logStreamName, logEvents);
        } catch (DataAlreadyAcceptedException e) {
            // Don't do anything since the data already exists.
            logger.atError().cause(e).log("Dont do anything.");
        } catch (ResourceNotFoundException e) {
            // Handle no log group/log stream
            logger.atError().cause(e).log("Create.");
        } catch (AwsServiceException e) {
            // Back off for some time and then retry
            logger.atError().cause(e).log("No idea.");
        }
        return false;
    }

    private void createNewLogGroup(String logGroupName) {
        CreateLogGroupRequest request = CreateLogGroupRequest.builder().logGroupName(logGroupName).build();
        try {
            this.cloudWatchLogsClient.createLogGroup(request);
        } catch (ResourceAlreadyExistsException e) {
            // Don't do anything if the resource already exists.
            logger.atError().cause(e).log("Dont do anything.");
        } catch (LimitExceededException e) {
            // Back off for some time before retrying.
            logger.atError().cause(e).log("Wait for sometime.");
        }
    }

    private void createNewLogSteam(String logGroupName, String logStreamName) {
        CreateLogStreamRequest request = CreateLogStreamRequest.builder()
                .logGroupName(logGroupName)
                .logStreamName(logStreamName)
                .build();
        try {
            this.cloudWatchLogsClient.createLogStream(request);
        } catch (ResourceAlreadyExistsException e) {
            // Don't do anything if the resource already exists.
            logger.atError().cause(e).log("Dont do anything.");
        } catch (LimitExceededException e) {
            // Back off for some time before retrying.
            logger.atError().cause(e).log("Wait for sometime.");
        }
    }

    private String getSequenceToken(String logGroupName, String logStreamName) {
        DescribeLogStreamsRequest request = DescribeLogStreamsRequest.builder()
                .logGroupName(logGroupName)
                .logStreamNamePrefix(logStreamName)
                .build();
        DescribeLogStreamsResponse response = this.cloudWatchLogsClient.describeLogStreams(request);
        return response.nextToken();
    }

    /**
     * Keeping this package-private for unit tests.
     *
     * @param logGroupName      The CloudWatch log group
     * @param logStreamName     The CloudWatch log stream within the log group
     * @param nextSequenceToken The next token to be associated to the PutEvents request for the log group and stream.
     */
    void addNextSequenceToken(String logGroupName, String logStreamName, String nextSequenceToken) {
        Map<String, String> logStreamToSequenceTokenMap =
                logGroupsToSequenceTokensMap.getOrDefault(logGroupName, new ConcurrentHashMap<>());
        logStreamToSequenceTokenMap.put(logStreamName, nextSequenceToken);
        // TODO: clean up old streams/tokens. Maybe allow a mex of 5 streams for each log group.
        logGroupsToSequenceTokensMap.put(logGroupName, logStreamToSequenceTokenMap);
    }
}
