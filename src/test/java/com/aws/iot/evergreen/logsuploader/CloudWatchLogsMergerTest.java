/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttempt;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentLogFileInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentType;
import com.aws.iot.evergreen.logsuploader.model.LogFileInformation;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;

import java.io.File;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_AWS_REGION;
import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.logsuploader.CloudWatchLogsMerger.DEFAULT_LOG_GROUP_NAME;
import static com.aws.iot.evergreen.logsuploader.CloudWatchLogsMerger.DEFAULT_LOG_STREAM_NAME;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class CloudWatchLogsMergerTest extends EGServiceTestUtil {
    private static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH);
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;

    private CloudWatchLogsMerger merger;

    @BeforeEach
    public void startup() {
        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        Topic regionTopic = Topic.of(context, DEVICE_PARAM_AWS_REGION, "testRegion");
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        when(mockDeviceConfiguration.getAWSRegion()).thenReturn(regionTopic);
    }

    @AfterEach
    public void cleanup() { }

    @Test
    public void GIVEN_one_component_one_file_less_than_max_WHEN_merge_THEN_reads_entire_file()
            throws URISyntaxException {
        Set<ComponentLogFileInformation> componentLogFileInformation = new HashSet<>();
        File file1 = new File(getClass().getResource("testlogs2.log").toURI());
        List<LogFileInformation> logFileInformationSet = new ArrayList<>();
        logFileInformationSet.add(LogFileInformation.builder().startPosition(0).file(file1).build());
        componentLogFileInformation.add(ComponentLogFileInformation.builder()
                .name("TestComponent")
                .multiLineStartPattern(Pattern.compile("^[^\\s]+(\\s+[^\\s]+)*$"))
                .desiredLogLevel(Level.INFO)
                .componentType(ComponentType.GreenGrassSystemComponent)
                .logFileInformationList(logFileInformationSet)
                .build());
        merger = new CloudWatchLogsMerger(mockDeviceConfiguration);
        CloudWatchAttempt attempt = merger.performKWayMerge(componentLogFileInformation);
        assertNotNull(attempt);

        assertNotNull(attempt.getLogGroupsToLogStreamsMap());
        assertThat(attempt.getLogGroupsToLogStreamsMap().entrySet(), IsNot.not(IsEmptyCollection.empty()));
        String logGroup = calculateLogGroupName(ComponentType.GreenGrassSystemComponent, "testRegion", "TestComponent");
        assertTrue(attempt.getLogGroupsToLogStreamsMap().containsKey(logGroup));
        Map<String, CloudWatchAttemptLogInformation> logGroupInfo = attempt.getLogGroupsToLogStreamsMap().get(logGroup);
        String logStream = calculateLogStreamName("testThing");
        assertTrue(logGroupInfo.containsKey(logStream));
        CloudWatchAttemptLogInformation logEventsForStream1 = logGroupInfo.get(logStream);
        assertNotNull(logEventsForStream1.getLogEvents());
        assertEquals(7, logEventsForStream1.getLogEvents().size());
        assertEquals(0, logEventsForStream1.getStartPosition());
        assertEquals(7, logEventsForStream1.getBytesRead());
        assertEquals("TestComponent", logEventsForStream1.getComponentName());
        assertEquals(file1.getAbsolutePath(), logEventsForStream1.getFileName());
    }

    @Test
    public void GIVEN_one_component_one_file_more_than_max_WHEN_merge_THEN_reads_partial_file() {
        merger = new CloudWatchLogsMerger(mockDeviceConfiguration);
    }

    @Test
    public void GIVEN_one_components_two_file_less_than_max_WHEN_merge_THEN_reads_and_merges_both_files()
            throws URISyntaxException {
        Set<ComponentLogFileInformation> componentLogFileInformation = new HashSet<>();
        File file1 = new File(getClass().getResource("testlogs2.log").toURI());
        File file2 = new File(getClass().getResource("testlogs1.log").toURI());
        List<LogFileInformation> logFileInformationSet = new ArrayList<>();
        logFileInformationSet.add(LogFileInformation.builder().startPosition(0).file(file1).build());
        logFileInformationSet.add(LogFileInformation.builder().startPosition(0).file(file2).build());
        componentLogFileInformation.add(ComponentLogFileInformation.builder()
                .name("TestComponent")
                .multiLineStartPattern(Pattern.compile("^[^\\s]+(\\s+[^\\s]+)*$"))
                .desiredLogLevel(Level.INFO)
                .componentType(ComponentType.GreenGrassSystemComponent)
                .logFileInformationList(logFileInformationSet)
                .build());
        merger = new CloudWatchLogsMerger(mockDeviceConfiguration);
        CloudWatchAttempt attempt = merger.performKWayMerge(componentLogFileInformation);

        assertNotNull(attempt);

        assertNotNull(attempt.getLogGroupsToLogStreamsMap());
        assertThat(attempt.getLogGroupsToLogStreamsMap().entrySet(), IsNot.not(IsEmptyCollection.empty()));
        String logGroup = calculateLogGroupName(ComponentType.GreenGrassSystemComponent, "testRegion", "TestComponent");
        assertTrue(attempt.getLogGroupsToLogStreamsMap().containsKey(logGroup));
        Map<String, CloudWatchAttemptLogInformation> logGroupInfo = attempt.getLogGroupsToLogStreamsMap().get(logGroup);
        String logStream = calculateLogStreamName("testThing");
        String logStream2 = "2020/02/10/{greengrass-fleet-id}/testThing";
        assertTrue(logGroupInfo.containsKey(logStream));
        assertTrue(logGroupInfo.containsKey(logStream2));
        CloudWatchAttemptLogInformation logEventsForStream1 = logGroupInfo.get(logStream);
        CloudWatchAttemptLogInformation logEventsForStream2 = logGroupInfo.get(logStream2);
        assertNotNull(logEventsForStream1.getLogEvents());
        assertEquals(7, logEventsForStream1.getLogEvents().size());
        assertEquals(0, logEventsForStream1.getStartPosition());
        assertEquals(7, logEventsForStream1.getBytesRead());
        assertEquals("TestComponent", logEventsForStream1.getComponentName());
        assertEquals(file1.getAbsolutePath(), logEventsForStream1.getFileName());

        assertNotNull(logEventsForStream2.getLogEvents());
        assertEquals(4, logEventsForStream2.getLogEvents().size());
        assertEquals(0, logEventsForStream2.getStartPosition());
        assertEquals(1252, logEventsForStream2.getBytesRead());
        assertEquals("TestComponent", logEventsForStream2.getComponentName());
        assertEquals(file2.getAbsolutePath(), logEventsForStream2.getFileName());
    }

    @Test
    public void GIVEN_two_components_one_file_less_than_max_WHEN_merge_THEN_reads_and_merges_both_files()
            throws URISyntaxException {
        Set<ComponentLogFileInformation> componentLogFileInformation = new HashSet<>();
        File file1 = new File(getClass().getResource("testlogs2.log").toURI());
        File file2 = new File(getClass().getResource("testlogs1.log").toURI());
        List<LogFileInformation> logFileInformationSet1 = new ArrayList<>();
        logFileInformationSet1.add(LogFileInformation.builder().startPosition(0).file(file1).build());
        List<LogFileInformation> logFileInformationSet2 = new ArrayList<>();
        logFileInformationSet2.add(LogFileInformation.builder().startPosition(0).file(file2).build());
        componentLogFileInformation.add(ComponentLogFileInformation.builder()
                .name("TestComponent")
                .desiredLogLevel(Level.INFO)
                .multiLineStartPattern(Pattern.compile("^[^\\s]+(\\s+[^\\s]+)*$"))
                .componentType(ComponentType.GreenGrassSystemComponent)
                .logFileInformationList(logFileInformationSet1)
                .build());
        componentLogFileInformation.add(ComponentLogFileInformation.builder()
                .name("TestComponent2")
                .desiredLogLevel(Level.INFO)
                .multiLineStartPattern(Pattern.compile("^[^\\s]+(\\s+[^\\s]+)*$"))
                .componentType(ComponentType.UserComponent)
                .logFileInformationList(logFileInformationSet2)
                .build());
        merger = new CloudWatchLogsMerger(mockDeviceConfiguration);
        CloudWatchAttempt attempt = merger.performKWayMerge(componentLogFileInformation);
        assertNotNull(attempt);

        assertNotNull(attempt.getLogGroupsToLogStreamsMap());
        assertThat(attempt.getLogGroupsToLogStreamsMap().entrySet(), IsNot.not(IsEmptyCollection.empty()));
        String logGroup = calculateLogGroupName(ComponentType.GreenGrassSystemComponent, "testRegion", "TestComponent");
        String logGroup2 = calculateLogGroupName(ComponentType.UserComponent, "testRegion", "TestComponent2");
        assertTrue(attempt.getLogGroupsToLogStreamsMap().containsKey(logGroup));
        assertTrue(attempt.getLogGroupsToLogStreamsMap().containsKey(logGroup2));
        Map<String, CloudWatchAttemptLogInformation> logGroupInfo = attempt.getLogGroupsToLogStreamsMap().get(logGroup);
        Map<String, CloudWatchAttemptLogInformation> logGroupInfo2 = attempt.getLogGroupsToLogStreamsMap().get(logGroup2);
        String logStream = calculateLogStreamName("testThing");
        String logStream2 = "2020/02/10/{greengrass-fleet-id}/testThing";
        assertTrue(logGroupInfo.containsKey(logStream));
        assertTrue(logGroupInfo2.containsKey(logStream2));
        CloudWatchAttemptLogInformation logEventsForStream1 = logGroupInfo.get(logStream);
        CloudWatchAttemptLogInformation logEventsForStream2 = logGroupInfo2.get(logStream2);
        assertEquals(7, logEventsForStream1.getLogEvents().size());
        assertEquals(0, logEventsForStream1.getStartPosition());
        assertEquals(7, logEventsForStream1.getBytesRead());
        assertEquals("TestComponent", logEventsForStream1.getComponentName());
        assertEquals(file1.getAbsolutePath(), logEventsForStream1.getFileName());

        assertEquals(4, logEventsForStream2.getLogEvents().size());
        assertEquals(0, logEventsForStream2.getStartPosition());
        assertEquals(1252, logEventsForStream2.getBytesRead());
        assertEquals("TestComponent2", logEventsForStream2.getComponentName());
        assertEquals(file2.getAbsolutePath(), logEventsForStream2.getFileName());
    }

    @Test
    public void GIVEN_two_components_one_file_more_than_max_WHEN_merge_THEN_reads_and_merges_partial_files() {
        merger = new CloudWatchLogsMerger(mockDeviceConfiguration);
    }

    private String calculateLogGroupName(ComponentType componentType, String awsRegion, String componentName) {
        return DEFAULT_LOG_GROUP_NAME
                .replace("{componentType}", componentType.toString())
                .replace("{region}", awsRegion)
                .replace("{name-of-component}", componentName);
    }

    private String calculateLogStreamName(String thingName) {
        synchronized (DATE_FORMATTER) {
            return DEFAULT_LOG_STREAM_NAME
                    .replace("{name-of-core}", thingName)
                    .replace("{date}", DATE_FORMATTER.format(new Date()));
        }
    }
}
