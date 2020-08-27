/*
 *  Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *   SPDX-License-Identifier: Apache-2.0
 */

package com.aws.iot.evergreen.logsuploader;

import com.aws.iot.evergreen.config.Topics;
import com.aws.iot.evergreen.dependency.ImplementsService;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.kernel.EvergreenService;
import com.aws.iot.evergreen.logging.impl.LogManager;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttempt;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentLogConfiguration;
import com.aws.iot.evergreen.logsuploader.model.ComponentLogFileInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentType;
import com.aws.iot.evergreen.logsuploader.model.LogFileInformation;
import com.aws.iot.evergreen.util.Coerce;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import javax.inject.Inject;

import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;

@ImplementsService(name = LogsUploaderService.LOGS_UPLOADER_SERVICE_TOPICS, version = "1.0.0")
public class LogsUploaderService extends EvergreenService {
    public static final String LOGS_UPLOADER_SERVICE_TOPICS = "aws.greengrass.logsuploader";
    public static final String LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC = "logsUploaderPeriodicUpdateIntervalSec";
    private static final String SYSTEM_LOGS_COMPONENT_NAME = "System";
    private static final int DEFAULT_PERIODIC_UPDATE_INTERVAL_SEC = 30_000;

    final Map<String, Instant> lastComponentUploadedLogFileInstantMap = new ConcurrentHashMap<>();
    final Map<String, CurrentProcessingFileInformation> componentCurrentProcessingLogFile =
            new ConcurrentHashMap<>();
    private final CloudWatchLogsUploader uploader;
    private final CloudWatchLogsMerger merger;
    private final Set<ComponentLogConfiguration> componentLogConfigurations = new HashSet<>();
    private final AtomicBoolean isCurrentlyUploading = new AtomicBoolean(false);
    private ScheduledFuture<?> periodicUpdateFuture;
    private int periodicUpdateIntervalSec;

    /**
     * Constructor.
     *
     * @param topics              The configuration coming from  kernel
     * @param uploader            {@link CloudWatchLogsUploader}
     * @param deviceConfiguration {@link DeviceConfiguration}
     */
    @Inject
    LogsUploaderService(Topics topics, CloudWatchLogsUploader uploader,
                        DeviceConfiguration deviceConfiguration, CloudWatchLogsMerger merger) {
        super(topics);
        this.uploader = uploader;
        this.merger = merger;

        updateThingName(Coerce.toString(deviceConfiguration.getThingName()));
        topics.lookup(DeviceConfiguration.DEVICE_PARAM_THING_NAME)
                .subscribe((why, node) -> updateThingName(Coerce.toString(node)));

        //TODO: only get this if the configuration state to upload system metrics to cloud.
        Optional<Path> logsDirectoryPath = LogManager.getLoggerDirectoryPath();
        logsDirectoryPath.ifPresent(path -> componentLogConfigurations.add(ComponentLogConfiguration.builder()
                // TODO: Have a better way to get this.
                .fileNameRegex(Pattern.compile("^evergreen.log\\w*"))
                .directoryPath(path)
                .name(SYSTEM_LOGS_COMPONENT_NAME)
                .componentType(ComponentType.GreenGrassSystemComponent)
                .build()));

        topics.lookup(PARAMETERS_CONFIG_KEY, LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC)
                .dflt(DEFAULT_PERIODIC_UPDATE_INTERVAL_SEC)
                .subscribe((why, newv) -> {
                    periodicUpdateIntervalSec = Coerce.toInt(newv);
                    if (periodicUpdateFuture != null) {
                        schedulePeriodicLogsUploaderUpdate();
                    }
                });
        schedulePeriodicLogsUploaderUpdate();
        this.uploader.registerAttemptStatus(LOGS_UPLOADER_SERVICE_TOPICS, this::handleCloudWatchAttemptStatus);

        //TODO: read configuration of components.
    }

    /**
     * Handle the attempt to upload logs to CloudWatch.
     *
     * @implSpec : The method gets the attempt from the uploader which has information about which log groups and
     *     log streams have been successfully uploaded to CloudWatch. Based on that information, it will update
     *     the appropriate information about each component.
     *     If a log group/stream has been successfully uploaded, there will be appropriate information about which
     *     log files did the logs belong to and what was the starting position for the logs in the stream and how many
     *     bytes worth of logs were part of the upload.
     *     If the file has been completely read and uploaded, the method will update the component information about the
     *     latest log file's information.
     *     If the file was partially read, then the method will update that information about what is the current
     *     processing log file for the component and what is the starting position of the next log line.
     *
     * @param cloudWatchAttempt The cloud watch attempt.
     */
    private void handleCloudWatchAttemptStatus(CloudWatchAttempt cloudWatchAttempt) {
        Map<String, Set<String>> completedLogFilePerComponent = new ConcurrentHashMap<>();
        Map<String, CurrentProcessingFileInformation> currentProcessingLogFilePerComponent = new ConcurrentHashMap<>();

        cloudWatchAttempt.getLogStreamUploadedMap().forEach((groupName, streamNames) -> {
            streamNames.forEach(streamName -> {

                CloudWatchAttemptLogInformation attemptLogInformation =
                        cloudWatchAttempt.getLogGroupsToLogStreamsMap().get(groupName).get(streamName);

                File file = new File(attemptLogInformation.getFileName());
                if (file.length() == attemptLogInformation.getBytesRead() + attemptLogInformation.getStartPosition()) {
                    Set<String> completedFileNames = completedLogFilePerComponent
                            .getOrDefault(attemptLogInformation.getComponentName(), new HashSet<>());
                    completedFileNames.add(attemptLogInformation.getFileName());
                    completedLogFilePerComponent.put(attemptLogInformation.getComponentName(), completedFileNames);
                    if (currentProcessingLogFilePerComponent.containsKey(attemptLogInformation.getComponentName())) {
                        CurrentProcessingFileInformation fileInformation = currentProcessingLogFilePerComponent
                                .get(attemptLogInformation.getComponentName());
                        if (fileInformation.fileName.equals(attemptLogInformation.getFileName())) {
                            currentProcessingLogFilePerComponent.remove(attemptLogInformation.getComponentName());
                        }
                    }
                } else {
                    if (completedLogFilePerComponent.containsKey(attemptLogInformation.getComponentName())
                            && completedLogFilePerComponent.get(attemptLogInformation.getComponentName())
                            .contains(attemptLogInformation.getFileName())) {
                        return;
                    }
                    CurrentProcessingFileInformation processingFileInformation =
                            CurrentProcessingFileInformation.builder()
                                    .fileName(attemptLogInformation.getFileName())
                                    .startPosition(attemptLogInformation.getStartPosition()
                                            + attemptLogInformation.getBytesRead())
                                    .build();
                    currentProcessingLogFilePerComponent.put(attemptLogInformation.getComponentName(),
                            processingFileInformation);
                }
            });

        });

        completedLogFilePerComponent.forEach((componentName, fileNames) ->
                fileNames.stream().map(File::new).forEach(file -> {
                    if (!lastComponentUploadedLogFileInstantMap.containsKey(componentName)
                            || lastComponentUploadedLogFileInstantMap.get(componentName)
                            .isBefore(Instant.ofEpochMilli(file.lastModified()))) {
                        lastComponentUploadedLogFileInstantMap.put(componentName,
                                Instant.ofEpochMilli(file.lastModified()));
                    }
                }));
        currentProcessingLogFilePerComponent.forEach(componentCurrentProcessingLogFile::put);
        isCurrentlyUploading.set(false);

        //TODO: Persist this information to the disk.
    }

    private void schedulePeriodicLogsUploaderUpdate() {
        if (periodicUpdateFuture != null) {
            periodicUpdateFuture.cancel(false);
        }

        ScheduledExecutorService ses = getContext().get(ScheduledExecutorService.class);
        this.periodicUpdateFuture = ses.scheduleWithFixedDelay(this::mergeLogsAndUpload,
                periodicUpdateIntervalSec, periodicUpdateIntervalSec, TimeUnit.SECONDS);
    }

    /**
     * Merge all available log files and upload the logs to the cloud.
     *
     * @implSpec : The service will first check if there was already a cloudwatch attempt in progess. If so, it will
     *     return.
     *     It will then go through the components log configuration map and check if there are any log files from that
     *     component that needs to be uploaded to the cloud.
     *     The service will first get all the files from the log file directory and then sort them by the last modified
     *     time.
     *     It will then get all the log files which have not yet been uploaded to the cloud. This is done by checking
     *     the last uploaded log file time for that component.
     *     The service will then perform a k-way merge for all components log files and then upload the logs to cloud
     *     watch.
     */
    private void mergeLogsAndUpload() {
        // If there is already an upload ongoing, don't do anything. Wait for the next schedule to trigger to
        // upload new logs.
        if (!isCurrentlyUploading.compareAndSet(false, true)) {
            return;
        }
        Map<String, ComponentLogFileInformation> filePaths = new HashMap<>();
        componentLogConfigurations.forEach(componentLogConfiguration -> {
            Instant lastUploadedLogFileTimeMs =
                    lastComponentUploadedLogFileInstantMap.getOrDefault(componentLogConfiguration.getName(),
                            Instant.EPOCH);
            File folder = new File(componentLogConfiguration.getDirectoryPath().toUri());
            List<File> allFiles = new ArrayList<>();
            try {
                File[] files = folder.listFiles();
                if (files != null) {
                    // Sort the files by the last modified time.
                    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                    for (File file : files) {
                        if (file.isFile()
                                && lastUploadedLogFileTimeMs.isBefore(Instant.ofEpochMilli(file.lastModified()))
                                && componentLogConfiguration.getFileNameRegex().matcher(file.getName()).find()
                                && file.length() > 0) {
                            allFiles.add(file);
                        }
                    }
                }
                // If there are no files for the component, then return.
                if (allFiles.isEmpty()) {
                    return;
                }

                // Don't consider the active log file.
                allFiles = allFiles.subList(0, allFiles.size() - 1);
                allFiles.forEach(file -> {
                    long startPosition = 0;
                    // If the file was paritially read in the previous run, then get the starting position for new
                    // log lines.
                    if (componentCurrentProcessingLogFile.containsKey(componentLogConfiguration.getName())) {
                        CurrentProcessingFileInformation processingFileInformation = componentCurrentProcessingLogFile
                                .get(componentLogConfiguration.getName());
                        if (processingFileInformation.fileName.equals(file.getAbsolutePath())) {
                            startPosition = processingFileInformation.startPosition;
                        }
                    }
                    LogFileInformation logFileInformation = LogFileInformation.builder()
                            .file(file)
                            .startPosition(startPosition)
                            .build();
                    ComponentLogFileInformation componentLogFileInformation =
                            filePaths.getOrDefault(componentLogConfiguration.getName(),
                                    ComponentLogFileInformation.builder()
                                            .name(componentLogConfiguration.getName())
                                            .multiLineStartPattern(componentLogConfiguration.getMultiLineStartPattern())
                                            .desiredLogLevel(componentLogConfiguration.getMinimumLogLevel())
                                            .componentType(componentLogConfiguration.getComponentType())
                                            .logFileInformationList(new ArrayList<>())
                                            .build());
                    componentLogFileInformation.getLogFileInformationList().add(logFileInformation);
                    filePaths.put(componentLogConfiguration.getName(), componentLogFileInformation);
                });
            } catch (SecurityException e) {
                logger.atError().cause(e).log("Unable to get log files for {} from {}",
                        componentLogConfiguration.getName(), componentLogConfiguration.getDirectoryPath());
            }
        });
        CloudWatchAttempt cloudWatchAttempt = merger.performKWayMerge(filePaths.values());
        uploader.upload(cloudWatchAttempt);
    }

    private void updateThingName(String newThingName) {
        if (newThingName != null) {
            merger.setThingName(newThingName);
        }
    }

    @Override
    @SuppressWarnings("PMD.UselessOverridingMethod")
    public void startup() throws InterruptedException {
        // Need to override the function for tests.
        super.startup();
    }

    @Override
    public void shutdown() {
        if (!this.periodicUpdateFuture.isCancelled()) {
            this.periodicUpdateFuture.cancel(true);
        }
    }

    /**
     * Used for unit tests.
     *
     * @param componentLogConfiguration {@link ComponentLogConfiguration}
     * @param instant                   the last uploaded time stamp completely uploaded.
     */
    void addLastUploadedLogFileTimeMs(ComponentLogConfiguration componentLogConfiguration, Instant instant) {
        lastComponentUploadedLogFileInstantMap.put(componentLogConfiguration.getName(), instant);
    }

    @Builder
    @Getter
    @Data
    static class CurrentProcessingFileInformation {
        private String fileName;
        private long startPosition;
    }
}
