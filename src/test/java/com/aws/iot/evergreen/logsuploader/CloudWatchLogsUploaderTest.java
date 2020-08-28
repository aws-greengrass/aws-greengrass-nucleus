/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader;

import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttempt;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogFileInformation;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogInformation;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import com.aws.iot.evergreen.util.CloudWatchClientFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogGroupRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.CreateLogStreamRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsRequest;
import software.amazon.awssdk.services.cloudwatchlogs.model.PutLogEventsResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class CloudWatchLogsUploaderTest extends EGServiceTestUtil  {
    @Mock
    private CloudWatchClientFactory mockCloudWatchClientFactory;
    @Mock
    private CloudWatchLogsClient mockCloudWatchLogsClient;
    @Captor
    private ArgumentCaptor<PutLogEventsRequest> putLogEventsRequestArgumentCaptor;

    private CloudWatchLogsUploader uploader;

    @BeforeEach
    public void setup() {
        when(mockCloudWatchClientFactory.getCloudWatchLogsClient()).thenReturn(mockCloudWatchLogsClient);
    }

    @AfterEach
    public void cleanup() {

    }

    @Test
    public void GIVEN_mock_cloud_watch_attempt_WHEN_put_events_called_THEN_successfully_uploads_all_log_events() {
        String mockGroupName = "testGroup";
        String mockStreamNameForGroup = "testStream";
        String mockSequenceToken = UUID.randomUUID().toString();
        String mockNextSequenceToken = UUID.randomUUID().toString();
        CloudWatchAttempt attempt = new CloudWatchAttempt();
        Map<String, Map<String, CloudWatchAttemptLogInformation>> logGroupsMap = new ConcurrentHashMap<>();
        Map<String, CloudWatchAttemptLogInformation> logSteamForGroup1Map = new ConcurrentHashMap<>();
        List<InputLogEvent> inputLogEventsForStream1OfGroup1 = new ArrayList<>();
        inputLogEventsForStream1OfGroup1.add(InputLogEvent.builder()
                .timestamp(Instant.now().toEpochMilli())
                .message("test")
                .build());
        inputLogEventsForStream1OfGroup1.add(InputLogEvent.builder()
                .timestamp(Instant.now().toEpochMilli())
                .message("test2")
                .build());
        Map<String, CloudWatchAttemptLogFileInformation> attemptLogFileInformationMap = new HashMap<>();
        attemptLogFileInformationMap.put("test.log", CloudWatchAttemptLogFileInformation.builder()
                .startPosition(0)
                .bytesRead(100)
                .build());
        logSteamForGroup1Map.put(mockStreamNameForGroup,
                CloudWatchAttemptLogInformation.builder()
                        .logEvents(inputLogEventsForStream1OfGroup1)
                        .attemptLogFileInformationList(attemptLogFileInformationMap)
                        .build());
        logGroupsMap.put(mockGroupName, logSteamForGroup1Map);
        attempt.setLogGroupsToLogStreamsMap(logGroupsMap);
        PutLogEventsResponse response = PutLogEventsResponse.builder().nextSequenceToken(mockNextSequenceToken).build();
        when(mockCloudWatchLogsClient.putLogEvents(any(PutLogEventsRequest.class))).thenReturn(response);

        uploader = new CloudWatchLogsUploader(mockCloudWatchClientFactory);
        uploader.addNextSequenceToken(mockGroupName, mockStreamNameForGroup, mockSequenceToken);
        uploader.upload(attempt);

        verify(mockCloudWatchLogsClient, times(1)).createLogStream(any(CreateLogStreamRequest.class));
        verify(mockCloudWatchLogsClient, times(1)).createLogGroup(any(CreateLogGroupRequest.class));
        verify(mockCloudWatchLogsClient, times(1)).putLogEvents(putLogEventsRequestArgumentCaptor.capture());

        Set<String> messageTextToCheck = new HashSet<>();
        messageTextToCheck.add("test");
        messageTextToCheck.add("test2");

        assertNotNull(putLogEventsRequestArgumentCaptor.getValue());
        PutLogEventsRequest request = putLogEventsRequestArgumentCaptor.getValue();
        assertTrue(request.hasLogEvents());
        assertEquals(mockGroupName, request.logGroupName());
        assertEquals(mockStreamNameForGroup, request.logStreamName());
        assertEquals(mockSequenceToken, request.sequenceToken());
        assertEquals(2, request.logEvents().size());
        request.logEvents().forEach(inputLogEvent -> {
            assertNotNull(inputLogEvent.message());
            assertNotNull(inputLogEvent.timestamp());
            messageTextToCheck.remove(inputLogEvent.message());
        });
        assertEquals(0, messageTextToCheck.size());
        assertEquals(mockNextSequenceToken, uploader.logGroupsToSequenceTokensMap.get(mockGroupName).get(mockStreamNameForGroup));
    }
}
