package com.aws.iot.evergreen.logsuploader;

import com.aws.iot.evergreen.config.Topic;
import com.aws.iot.evergreen.deployment.DeviceConfiguration;
import com.aws.iot.evergreen.logging.impl.config.EvergreenLogConfig;
import com.aws.iot.evergreen.logging.impl.config.LogStore;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttempt;
import com.aws.iot.evergreen.logsuploader.model.CloudWatchAttemptLogInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentLogFileInformation;
import com.aws.iot.evergreen.logsuploader.model.ComponentType;
import com.aws.iot.evergreen.testcommons.testutilities.EGExtension;
import com.aws.iot.evergreen.testcommons.testutilities.EGServiceTestUtil;
import org.hamcrest.collection.IsEmptyCollection;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.aws.iot.evergreen.deployment.DeviceConfiguration.DEVICE_PARAM_THING_NAME;
import static com.aws.iot.evergreen.logsuploader.LogsUploaderService.LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC;
import static com.aws.iot.evergreen.packagemanager.KernelConfigResolver.PARAMETERS_CONFIG_KEY;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, EGExtension.class})
public class LogsUploaderServiceTest extends EGServiceTestUtil  {
    @Mock
    private CloudWatchLogsUploader mockUploader;
    @Mock
    private CloudWatchLogsMerger mockMerger;
    @Mock
    private DeviceConfiguration mockDeviceConfiguration;
    @Captor
    private ArgumentCaptor<Set<ComponentLogFileInformation>> componentLogsInformationCaptor;
    @Captor
    private ArgumentCaptor<Consumer<CloudWatchAttempt>> callbackCaptor;

    private static final Path directoryPath = Paths.get(System.getProperty("user.dir"));
    private LogsUploaderService logsUploaderService;
    private ScheduledThreadPoolExecutor ses;

    @BeforeAll
    static void setupBefore() throws IOException, InterruptedException {
        EvergreenLogConfig.getInstance().setLevel(Level.TRACE);
        EvergreenLogConfig.getInstance().setStoreType(LogStore.FILE);
        EvergreenLogConfig.getInstance().setStorePath(directoryPath);
        for (int i = 0; i < 5; i++) {
            File file = new File(directoryPath.resolve("evergreen.log_test-" + i).toUri());
            file.createNewFile();
            assertTrue(file.setReadable(true));
            assertTrue(file.setWritable(true));

            try (OutputStream fileOutputStream = Files.newOutputStream(file.toPath())) {
                fileOutputStream.write("TEST".getBytes(StandardCharsets.UTF_8));
            }
            TimeUnit.SECONDS.sleep(1);
        }
        File currentFile = new File(directoryPath.resolve("evergreen.log").toUri());
        try (OutputStream currentFileOutputStream =Files.newOutputStream(currentFile.toPath())) {
            currentFileOutputStream.write("TEST".getBytes(StandardCharsets.UTF_8));
        }
    }

    @AfterAll
    static void cleanUpAfter() {
        final File folder = new File(directoryPath.toUri());
        final File[] files = folder.listFiles();
        if (files != null) {
            for ( final File file : files ) {
                if ( file.getName().startsWith("evergreen.log") && !file.delete() ) {
                    System.err.println( "Can't remove " + file.getAbsolutePath() );
                }
            }
        }
    }

    @BeforeEach
    public void setup() {
        serviceFullName = "LogsUploaderService";
        initializeMockedConfig();
        ses = new ScheduledThreadPoolExecutor(4);
        when(context.get(ScheduledExecutorService.class)).thenReturn(ses);

        Topic thingNameTopic = Topic.of(context, DEVICE_PARAM_THING_NAME, "testThing");
        when(mockDeviceConfiguration.getThingName()).thenReturn(thingNameTopic);
        when(config.lookup(DEVICE_PARAM_THING_NAME)).thenReturn(thingNameTopic);

    }

    @AfterEach
    public void cleanup() {
        ses.shutdownNow();
        logsUploaderService.shutdown();
    }

    @Test
    public void GIVEN_system_log_files_to_be_uploaded_WHEN_merger_merges_THEN_we_get_all_log_files()
            throws InterruptedException {
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC, "3");
        when(config.lookup(PARAMETERS_CONFIG_KEY, LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC))
                .thenReturn(periodicUpdateIntervalMsTopic);
        when(mockMerger.performKWayMerge(componentLogsInformationCaptor.capture())).thenReturn(new CloudWatchAttempt());

        logsUploaderService = new LogsUploaderService(config, mockUploader, mockDeviceConfiguration, mockMerger);
        logsUploaderService.startup();

        TimeUnit.SECONDS.sleep(5);

        assertNotNull(componentLogsInformationCaptor.getValue());
        Collection<ComponentLogFileInformation> componentLogFileInformationList = componentLogsInformationCaptor.getValue();
        assertFalse(componentLogFileInformationList.isEmpty());
        componentLogFileInformationList.iterator().forEachRemaining(componentLogFileInformation -> {
            assertEquals("System", componentLogFileInformation.getName());
            assertEquals(ComponentType.GreenGrassSystemComponent, componentLogFileInformation.getComponentType());
            assertEquals(Level.INFO, componentLogFileInformation.getDesiredLogLevel());
            assertNotNull(componentLogFileInformation.getLogFileInformationList());
            assertThat(componentLogFileInformation.getLogFileInformationList(), IsNot.not(IsEmptyCollection.empty()));
            assertTrue(componentLogFileInformation.getLogFileInformationList().size() >= 5);
        });

        verify(mockUploader, times(1)).upload(any(CloudWatchAttempt.class));
    }

    @Test
    public void GIVEN_cloud_watch_attempt_handler_WHEN_attempt_completes_THEN_successfully_updates_states_for_each_component()
            throws InterruptedException, URISyntaxException {
        Topic periodicUpdateIntervalMsTopic = Topic.of(context, LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC, "1000");
        when(config.lookup(PARAMETERS_CONFIG_KEY, LOGS_UPLOADER_PERIODIC_UPDATE_INTERVAL_SEC))
                .thenReturn(periodicUpdateIntervalMsTopic);

        CloudWatchAttempt attempt = new CloudWatchAttempt();
        Map<String, Map<String, CloudWatchAttemptLogInformation>> logGroupsToLogStreamsMap = new HashMap<>();
        Map<String, CloudWatchAttemptLogInformation> logStreamsToLogInformationMap = new HashMap<>();
        File file1 = new File(getClass().getResource("testlogs2.log").toURI());
        File file2 = new File(getClass().getResource("testlogs1.log").toURI());
        CloudWatchAttemptLogInformation attemptLogInformation1 = CloudWatchAttemptLogInformation.builder()
                .bytesRead(13)
                .startPosition(0)
                .fileName(file1.getAbsolutePath())
                .componentName("TestComponent")
                .build();
        CloudWatchAttemptLogInformation attemptLogInformation2 = CloudWatchAttemptLogInformation.builder()
                .bytesRead(1061)
                .startPosition(0)
                .fileName(file2.getAbsolutePath())
                .componentName("TestComponent2")
                .build();
        logStreamsToLogInformationMap.put("testStream", attemptLogInformation1);
        logStreamsToLogInformationMap.put("testStream2", attemptLogInformation2);
        logGroupsToLogStreamsMap.put("testGroup", logStreamsToLogInformationMap);
        Map<String, List<String>> logStreamUploadedMap = new HashMap<>();
        logStreamUploadedMap.put("testGroup", Arrays.asList("testStream", "testStream2"));
        attempt.setLogGroupsToLogStreamsMap(logGroupsToLogStreamsMap);
        attempt.setLogStreamUploadedMap(logStreamUploadedMap);
        doNothing().when(mockUploader).registerAttemptStatus(anyString(), callbackCaptor.capture());

        logsUploaderService = new LogsUploaderService(config, mockUploader, mockDeviceConfiguration, mockMerger);
        logsUploaderService.startup();

        callbackCaptor.getValue().accept(attempt);

        assertNotNull(logsUploaderService.lastComponentUploadedLogFileInstantMap);
        assertThat(logsUploaderService.lastComponentUploadedLogFileInstantMap.entrySet(), IsNot.not(IsEmptyCollection.empty()));
        assertTrue(logsUploaderService.lastComponentUploadedLogFileInstantMap.containsKey("TestComponent"));
        assertEquals(Instant.ofEpochMilli(file1.lastModified()), logsUploaderService.lastComponentUploadedLogFileInstantMap.get("TestComponent"));
        assertNotNull(logsUploaderService.componentCurrentProcessingLogFile);
        assertThat(logsUploaderService.componentCurrentProcessingLogFile.entrySet(), IsNot.not(IsEmptyCollection.empty()));
        assertTrue(logsUploaderService.componentCurrentProcessingLogFile.containsKey("TestComponent2"));
        assertEquals(file2.getAbsolutePath(), logsUploaderService.componentCurrentProcessingLogFile.get("TestComponent2").getFileName());
        assertEquals(1061, logsUploaderService.componentCurrentProcessingLogFile.get("TestComponent2").getStartPosition());
    }
}
