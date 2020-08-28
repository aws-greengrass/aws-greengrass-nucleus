/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *  SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader;

import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logging.impl.EvergreenStructuredLogMessage;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttempt;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogFileInformation;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentLogFileInformation;
import com.aws.iot.evergreen.util.Coerce;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Setter;
import org.slf4j.event.Level;
import software.amazon.awssdk.services.cloudwatchlogs.model.InputLogEvent;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;

public class CloudWatchLogsMerger {
    private static final int EVENT_STORAGE_OVERHEAD = 26;
    private static final int MAX_BATCH_SIZE = 1024 * 1024;
    public static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("yyyy/MM/dd", Locale.ENGLISH);
    public static final String DEFAULT_LOG_GROUP_NAME =
            "/aws/greengrass/{componentType}/{region}/{name-of-component}";
    public static final String DEFAULT_LOG_STREAM_NAME =
            "{date}/{greengrass-fleet-id}/{name-of-core}";
    private static final ObjectMapper DESERIALIZER = new ObjectMapper();
    @Setter
    private String thingName;
    @Setter
    private String awsRegion;

    @Inject
    public CloudWatchLogsMerger(DeviceConfiguration deviceConfiguration) {
        this.thingName = Coerce.toString(deviceConfiguration.getThingName());
        this.awsRegion = Coerce.toString(deviceConfiguration.getAWSRegion());
    }

    /**
     * Does a k-way merge on all the files from all components.
     *
     * @param componentLogFileInformation log files information for each component to read logs from.
     * @return CloudWatch attempt containing information needed to upload logs from different component to the cloud.
     * @implSpec : The merger has a list of components along with their associated log file information.
     *     The merger picks the first file from each component(which are sorted in an ascending order of
     *     last modified time)
     *     The merger will then read the lines from the file until a new log line is not found (using the multiline
     *     start pattern) or until the file has not reached the end.
     *     It will then try to determine if the file is in {@link EvergreenStructuredLogMessage} format or not. If it
     *     is, it will look the minimum desired log level and the current log line log level and determine if this log
     *     line should be uploaded.
     *     If the log line is not in {@link EvergreenStructuredLogMessage} format, then we will directly add the log
     *     line to be uploaded.
     *     The merger will continue to merge until either the MAX_BATCH_SIZE is reached or if all the files have been
     *     read.
     */
    public CloudWatchAttempt performKWayMerge(Collection<ComponentLogFileInformation> componentLogFileInformation) {
        AtomicInteger totalBytesRead = new AtomicInteger();
        AtomicInteger totalCompletelyReadAllComponentsCount = new AtomicInteger();
        CloudWatchAttempt attempt = new CloudWatchAttempt();
        Map<String, Map<String, CloudWatchAttemptLogInformation>> logGroupsMap = new ConcurrentHashMap<>();
        Map<String, RandomAccessFile> fileNameToFileMap = new ConcurrentHashMap<>();
        Map<String, StringBuilder> fileNameToTempDataMap = new ConcurrentHashMap<>();
        Map<String, Long> fileNameToTempStartPositionMap = new ConcurrentHashMap<>();
        AtomicBoolean reachedMaxSize = new AtomicBoolean(false);

        while (totalCompletelyReadAllComponentsCount.get() != componentLogFileInformation.size()
                && !reachedMaxSize.get()) {
            componentLogFileInformation.forEach(logFileInformation -> {
                if (logFileInformation.getLogFileInformationList().isEmpty()) {
                    return;
                }
                File file = logFileInformation.getLogFileInformationList().get(0).getFile();
                long initialStartPosition =
                        logFileInformation.getLogFileInformationList().get(0).getStartPosition();
                String fileName = file.getAbsolutePath();
                try {
                    String logGroupName = DEFAULT_LOG_GROUP_NAME
                            .replace("{componentType}", logFileInformation.getComponentType().toString())
                            .replace("{region}", awsRegion)
                            .replace("{name-of-component}", logFileInformation.getName());
                    //TODO: replace fleet config here.
                    String logStreamName = DEFAULT_LOG_STREAM_NAME
                            .replace("{name-of-core}", thingName);
                    Map<String, CloudWatchAttemptLogInformation> logStreamsMap =
                            logGroupsMap.getOrDefault(logGroupName, new ConcurrentHashMap<>());

                    // If we have read the file already, we are at the correct offset in the file to start reading from
                    // Let's get that file handle to read the new log line.
                    //TODO: This does not support the full Unicode character set. May need to rethink?
                    RandomAccessFile raf;
                    long startPosition = 0;
                    if (fileNameToFileMap.containsKey(fileName)) {
                        raf = fileNameToFileMap.get(fileName);
                    } else {
                        raf = new RandomAccessFile(file, "r");
                        fileNameToFileMap.put(fileName, raf);
                        raf.seek(initialStartPosition);
                        startPosition = initialStartPosition;
                    }

                    // If we had gotten a complete log line in the previous run, we have partially read the new log line
                    // already and stored it in the SB.
                    StringBuilder data;
                    if (fileNameToTempDataMap.containsKey(fileName)) {
                        data = fileNameToTempDataMap.get(fileName);
                    } else {
                        data = new StringBuilder(raf.readLine());
                    }

                    if (fileNameToTempStartPositionMap.containsKey(fileName)) {
                        startPosition = fileNameToTempStartPositionMap.get(fileName);
                    }

                    // Run the loop until we detect that the log file is completely read, or  that we have a complete
                    // log line in our String Builder.
                    while (true) {
                        try {
                            long tempStartPosition = raf.getFilePointer();
                            String partialLogLine = raf.readLine();
                            // If we do not get any data from the file, we have reached the end of the file.
                            // and we add the log line into our input logs event list since we are currently only
                            // working on rotated files, this will be guaranteed to be a complete log line.
                            if (partialLogLine == null) {
                                reachedMaxSize.set(processLogLine(totalBytesRead,
                                        logFileInformation.getDesiredLogLevel(), logStreamName,
                                        logStreamsMap, data, fileName, startPosition, logFileInformation.getName(),
                                        tempStartPosition));
                                logFileInformation.getLogFileInformationList().remove(0);
                                if (logFileInformation.getLogFileInformationList().isEmpty()) {
                                    totalCompletelyReadAllComponentsCount.getAndIncrement();
                                }
                                //totalCompletelyReadAllComponentsCount.getAndIncrement();
                                fileNameToTempDataMap.remove(fileName);
                                fileNameToTempStartPositionMap.remove(fileName);
                                break;
                            }

                            // If the new log line read from the file has the multiline separator, that means that
                            // the string builder we have appended data to until now, has a complete log line.
                            // Let's add that in the input logs event list.
                            if (logFileInformation.getMultiLineStartPattern().matcher(partialLogLine).find()) {
                                reachedMaxSize.set(processLogLine(totalBytesRead,
                                        logFileInformation.getDesiredLogLevel(), logStreamName,
                                        logStreamsMap, data, fileName, startPosition, logFileInformation.getName(),
                                        tempStartPosition));
                                fileNameToTempDataMap.put(fileName, new StringBuilder(partialLogLine));
                                fileNameToTempStartPositionMap.put(fileName, tempStartPosition);
                                break;
                            }

                            // Need to read more lines until we get a complete log line. Let's add this to the SB.
                            data.append(partialLogLine);
                        } catch (IOException e) {
                            // Probably reached end of file.
                            logFileInformation.getLogFileInformationList().remove(0);
                            if (logFileInformation.getLogFileInformationList().isEmpty()) {
                                totalCompletelyReadAllComponentsCount.getAndIncrement();
                            }
                            break;
                        }
                    }
                    logGroupsMap.put(logGroupName, logStreamsMap);
                } catch (IOException ignored) {
                    // File probaly does not exist.
                    logFileInformation.getLogFileInformationList().remove(0);
                    if (logFileInformation.getLogFileInformationList().isEmpty()) {
                        totalCompletelyReadAllComponentsCount.getAndIncrement();
                    }
                }

            });
        }
        attempt.setLogGroupsToLogStreamsMap(logGroupsMap);
        return attempt;
    }

    /**
     * Processes the log line by trying to deserialize the log line as a {@link EvergreenStructuredLogMessage}.
     * If log line is in the correct format, add the minimum log level filter and add the log event if the filter
     * passes.
     * If the log line is not in the {@link EvergreenStructuredLogMessage} format, we will add the log event to be
     * uploaded to CloudWatch.
     * Also creates the log stream name based on the timestamp value of the log line if it is in the
     * {@link EvergreenStructuredLogMessage} format.
     * Else, it will use the current date for the formatter.
     *
     * @param totalBytesRead  Total bytes read/added to the log events list.
     * @param desiredLogLevel The minimum desired log level.
     * @param logStreamName   The log stream name.
     * @param logStreamsMap   The log stream name map for the group.
     * @param data            The raw string data of the log line.
     */
    private boolean processLogLine(AtomicInteger totalBytesRead,
                                   Level desiredLogLevel,
                                   String logStreamName,
                                   Map<String, CloudWatchAttemptLogInformation> logStreamsMap,
                                   StringBuilder data,
                                   String fileName,
                                   long startPosition,
                                   String componentName,
                                   long currentPosition) {

        Optional<EvergreenStructuredLogMessage> logMessage = tryGetEvergreenStructuredLogMessage(data);
        if (logMessage.isPresent()) {
            synchronized (DATE_FORMATTER) {
                logStreamName = logStreamName.replace("{date}",
                        DATE_FORMATTER.format(new Date(logMessage.get().getTimestamp())));
            }
            CloudWatchAttemptLogInformation attemptLogInformation = logStreamsMap.getOrDefault(logStreamName,
                    CloudWatchAttemptLogInformation.builder()
                            .componentName(componentName)
                            .logEvents(new ArrayList<>())
                            .attemptLogFileInformationList(new HashMap<>())
                            .build());
            CloudWatchAttemptLogFileInformation attemptLogFileInformation =
                    attemptLogInformation.getAttemptLogFileInformationList().getOrDefault(fileName,
                            CloudWatchAttemptLogFileInformation.builder()
                                    .startPosition(startPosition)
                                    .build());
            int dataSize = data.toString().getBytes(StandardCharsets.UTF_8).length;
            boolean notReachedMaxSize = checkAndAddNewLogEvent(totalBytesRead, attemptLogInformation, data,
                    desiredLogLevel, logMessage.get(), dataSize);
            if (notReachedMaxSize) {
                return true;
            }

            attemptLogFileInformation.setBytesRead(currentPosition - attemptLogFileInformation.getStartPosition());
            attemptLogInformation.getAttemptLogFileInformationList().put(fileName, attemptLogFileInformation);
            logStreamsMap.put(logStreamName, attemptLogInformation);
        } else {
            synchronized (DATE_FORMATTER) {
                logStreamName = logStreamName.replace("{date}", DATE_FORMATTER.format(new Date()));
            }
            CloudWatchAttemptLogInformation attemptLogInformation = logStreamsMap.getOrDefault(logStreamName,
                    CloudWatchAttemptLogInformation.builder()
                            .componentName(componentName)
                            .logEvents(new ArrayList<>())
                            .build());
            CloudWatchAttemptLogFileInformation attemptLogFileInformation =
                    attemptLogInformation.getAttemptLogFileInformationList().getOrDefault(fileName,
                            CloudWatchAttemptLogFileInformation.builder()
                                    .startPosition(startPosition)
                                    .build());
            int dataSize = data.toString().getBytes(StandardCharsets.UTF_8).length;
            boolean notReachedMaxSize = addNewLogEvent(totalBytesRead, attemptLogInformation, data.toString(),
                    data.toString().getBytes(StandardCharsets.UTF_8).length);
            if (notReachedMaxSize) {
                return true;
            }
            attemptLogFileInformation.setBytesRead(currentPosition - attemptLogFileInformation.getStartPosition());
            attemptLogInformation.getAttemptLogFileInformationList().put(fileName, attemptLogFileInformation);
            logStreamsMap.put(logStreamName, attemptLogInformation);
        }
        return false;
    }

    private Optional<EvergreenStructuredLogMessage> tryGetEvergreenStructuredLogMessage(StringBuilder data) {
        try {
            return Optional.of(DESERIALIZER.readValue(data.toString(), EvergreenStructuredLogMessage.class));
        } catch (JsonProcessingException ignored) {
            // If unable to deserialize, then we treat it as a normal log line and do not need to smartly upload.
            return Optional.empty();
        }
    }

    private boolean checkAndAddNewLogEvent(AtomicInteger totalBytesRead,
                                           CloudWatchAttemptLogInformation attemptLogInformation,
                                           StringBuilder data,
                                           Level desiredLogLevel,
                                           EvergreenStructuredLogMessage logMessage,
                                           int dataSize) {
        Level currentLogLevel = Level.valueOf(logMessage.getLevel());
        if (currentLogLevel.toInt() < desiredLogLevel.toInt()) {
            return false;
        }
        return addNewLogEvent(totalBytesRead, attemptLogInformation, data.toString(), dataSize);
    }

    private boolean addNewLogEvent(AtomicInteger totalBytesRead, CloudWatchAttemptLogInformation attemptLogInformation,
                                   String data, int dataSize) {
        //TODO: handle different encodings? Possibly getting it from the config.
        if (totalBytesRead.get() + dataSize + 8 + EVENT_STORAGE_OVERHEAD > MAX_BATCH_SIZE) {
            return true;
        }
        totalBytesRead.addAndGet(dataSize + 8 + EVENT_STORAGE_OVERHEAD);

        InputLogEvent inputLogEvent = InputLogEvent.builder()
                .message(data)
                .timestamp(Instant.now().toEpochMilli()).build();
        attemptLogInformation.getLogEvents().add(inputLogEvent);
        return false;
    }
}
