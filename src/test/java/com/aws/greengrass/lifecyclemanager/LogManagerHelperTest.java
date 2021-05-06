/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.ChildChanged;
import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.config.UpdateBehaviorTree;
import com.aws.greengrass.config.WhatHappened;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.deployment.DeviceConfiguration;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import com.aws.greengrass.logging.impl.config.PersistenceConfig;
import com.aws.greengrass.logging.impl.config.model.LogConfigUpdate;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.NUCLEUS_CONFIG_LOGGING_TOPICS;
import static com.aws.greengrass.deployment.DeviceConfiguration.SYSTEM_NAMESPACE_KEY;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.logging.impl.config.LogConfig.LOGS_DIRECTORY;
import static com.aws.greengrass.logging.impl.config.LogConfig.LOG_FILE_EXTENSION;
import static com.aws.greengrass.telemetry.impl.MetricFactory.METRIC_LOGGER_PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.io.FileMatchers.aFileNamed;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class LogManagerHelperTest {
    @TempDir
    protected Path tempRootDir;
    @Mock
    private GreengrassService mockGreengrassService;
    @Mock
    private Kernel kernel;
    @Captor
    ArgumentCaptor<ChildChanged> childChangedArgumentCaptor;

    private static final Base64.Encoder base64Encoder = Base64.getEncoder();

    @BeforeEach
    void setup() {
        LogManager.setRoot(tempRootDir);
    }

    @AfterEach
    void cleanup() {
        LogConfig.getRootLogConfig().reset();
        LogConfig.getRootLogConfig().closeContext();
        for (LogConfig logConfig : LogManager.getLogConfigurations().values()) {
            logConfig.reset();
            logConfig.closeContext();
        }
        LogManager.getTelemetryConfig().reset();
    }

    @BeforeAll
    static void setupLogger() {
        LogManager.getRootLogConfiguration().setStore(LogStore.FILE);
        LogManager.getTelemetryLogger(METRIC_LOGGER_PREFIX + "test");
    }
    @AfterAll
    static void cleanupLogger() {
        LogManager.getTelemetryConfig().setStore(LogStore.CONSOLE);
        LogManager.getRootLogConfiguration().setStore(LogStore.CONSOLE);
    }

    @Test
    void GIVEN_mock_service_WHEN_getComponentLogger_THEN_logs_to_correct_log_file() throws IOException {
        when(mockGreengrassService.getServiceName()).thenReturn("MockService");

        LogConfig.getRootLogConfig().setStore(LogStore.FILE);
        Logger componentLogger = LogManagerHelper.getComponentLogger(mockGreengrassService);

        componentLogger.atInfo().log("Something");

        LogConfig logConfig = LogManager.getLogConfigurations().get("MockService");
        File logFile = new File(logConfig.getStoreName());
        assertThat(logFile, aFileNamed(equalToIgnoringCase("MockService.log")));
        List<String> lines = Files.readAllLines(logFile.toPath());
        assertThat(lines.get(0), containsString("Something"));

        File ggLogFile = new File(LogManager.getRootLogConfiguration().getStoreName());
        assertThat(ggLogFile, aFileNamed(equalToIgnoringCase("greengrass.log")));
        assertEquals(0, ggLogFile.length());
    }

    @Test
    void GIVEN_mock_service_logger_WHEN_reconfigure_THEN_change_applied_correctly() throws IOException {
        Path tempRootDir2 = tempRootDir.resolve("test_logs_" + Utils.generateRandomString(8));
        Path tempRootDir3 = tempRootDir.resolve("test_logs_" + Utils.generateRandomString(8));
        String mockServiceName = "MockService001";
        when(mockGreengrassService.getServiceName()).thenReturn(mockServiceName);
        LogConfig.getRootLogConfig().setStore(LogStore.FILE);
        Logger componentLogger = LogManagerHelper.getComponentLogger(mockGreengrassService);

        // change log file size
        LogConfigUpdate newConfig = LogConfigUpdate.builder().fileSizeKB(1L).build();
        LogManager.reconfigureAllLoggers(newConfig);

        // should apply change to all loggers
        LogConfig testLogConfig = LogManager.getLogConfigurations().get(mockServiceName);
        assertEquals(1, testLogConfig.getFileSizeKB());
        assertEquals(1, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(1, LogManager.getTelemetryConfig().getFileSizeKB());

        // log less than 1k bytes, should not rotate
        // 1 message of size 50 is about 128 bytes in TEXT format
        logRandomMessages(componentLogger, 50, 1);
        assertEquals(1, getLogFileCount(testLogConfig, mockServiceName));

        // log more than 2k bytes. Should rotate this time
        logRandomMessages(componentLogger, 50, 20);
        assertTrue(getLogFileCount(testLogConfig, mockServiceName) > 1);

        // change format and log directory
        newConfig = LogConfigUpdate.builder().format(LogFormat.JSON)
                .outputDirectory(tempRootDir2.toAbsolutePath().toString()).build();
        LogManager.reconfigureAllLoggers(newConfig);
        logRandomMessages(componentLogger, 50, 20);
        logRandomMessages(LogManager.getLogger("test"), 50, 20);
        // should output to new directory and still preserve log file size config
        assertEquals(tempRootDir2.toAbsolutePath(), testLogConfig.getStoreDirectory().toAbsolutePath());
        assertEquals(tempRootDir2.toAbsolutePath(),
                LogManager.getRootLogConfiguration().getStoreDirectory().toAbsolutePath());
        assertEquals(LogFormat.JSON, testLogConfig.getFormat());
        assertEquals(LogFormat.JSON, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(LogFormat.JSON, LogManager.getTelemetryConfig().getFormat());
        assertTrue(getLogFileCount(testLogConfig, mockServiceName) > 1);
        assertTrue(getLogFileCount(LogManager.getRootLogConfiguration(), PersistenceConfig.DEFAULT_STORE_NAME) > 1);
        // check log format is actually JSON
        File logFile = new File(testLogConfig.getStoreName());
        assertThat(logFile, aFileNamed(equalToIgnoringCase(mockServiceName + ".log")));
        List<String> lines = Files.readAllLines(logFile.toPath());
        ObjectMapper objectMapper = new ObjectMapper();
        assertDoesNotThrow(() -> {
            objectMapper.readValue(lines.get(0), Map.class);
        });

        // change totalLogsSizeKB, also change to another directory so it's cleaner
        newConfig = LogConfigUpdate.builder().totalLogsSizeKB(2L).format(LogFormat.TEXT)
                .outputDirectory(tempRootDir3.toAbsolutePath().toString()).build();
        LogManager.reconfigureAllLoggers(newConfig);
        // Get over the total limit. Must be separate calls. If log all at once, log may not rotate in time
        logRandomMessages(componentLogger, 50, 10);
        logRandomMessages(componentLogger, 50, 10);
        logRandomMessages(componentLogger, 50, 10);
        long numLogFilesBefore = getLogFileCount(testLogConfig, mockServiceName);
        logRandomMessages(componentLogger, 50, 20);
        // older rotated file should be deleted. Log file count should not change
        assertEquals(numLogFilesBefore, getLogFileCount(testLogConfig, mockServiceName));
    }

    @Test
    void GIVEN_mock_service_logger_WHEN_reset_THEN_reset_applied_correctly() {
        Path tempRootDir2 = tempRootDir.resolve("test_logs" + Utils.generateRandomString(8));
        String mockServiceName = "MockService001";
        when(mockGreengrassService.getServiceName()).thenReturn(mockServiceName);

        LogManagerHelper.getComponentLogger(mockGreengrassService);
        LogConfig testLogConfig = LogManager.getLogConfigurations().get(mockServiceName);
        PersistenceConfig defaultConfig = new PersistenceConfig(LOG_FILE_EXTENSION, LOGS_DIRECTORY);

        // first set a few non-default configs
        LogConfigUpdate newConfig = LogConfigUpdate.builder().format(LogFormat.JSON)
                .outputDirectory(tempRootDir2.toAbsolutePath().toString()).outputType(LogStore.CONSOLE).fileSizeKB(10L)
                .build();
        LogManager.reconfigureAllLoggers(newConfig);

        // reset individual configs
        LogManager.resetAllLoggers("format");
        assertEquals(defaultConfig.getFormat(), testLogConfig.getFormat());
        assertEquals(defaultConfig.getFormat(), LogManager.getRootLogConfiguration().getFormat());

        LogManager.resetAllLoggers("outputDirectory");
        assertEquals(defaultConfig.getStoreDirectory(), testLogConfig.getStoreDirectory());
        assertEquals(defaultConfig.getStoreDirectory(), LogManager.getRootLogConfiguration().getStoreDirectory());

        LogManager.resetAllLoggers("outputType");
        assertEquals(defaultConfig.getStore(), testLogConfig.getStore());
        assertEquals(defaultConfig.getStore(), LogManager.getRootLogConfiguration().getStore());

        LogManager.resetAllLoggers("fileSizeKB");
        assertEquals(defaultConfig.getFileSizeKB(), testLogConfig.getFileSizeKB());
        assertEquals(defaultConfig.getFileSizeKB(), LogManager.getRootLogConfiguration().getFileSizeKB());

        // reset all configs together
        LogManager.reconfigureAllLoggers(newConfig);
        LogManager.resetAllLoggers(null);
        assertEquals(defaultConfig.getFormat(), testLogConfig.getFormat());
        assertEquals(defaultConfig.getFormat(), LogManager.getRootLogConfiguration().getFormat());
        assertEquals(defaultConfig.getStoreDirectory(), testLogConfig.getStoreDirectory());
        assertEquals(defaultConfig.getStoreDirectory(), LogManager.getRootLogConfiguration().getStoreDirectory());
        assertEquals(defaultConfig.getStore(), testLogConfig.getStore());
        assertEquals(defaultConfig.getStore(), LogManager.getRootLogConfiguration().getStore());
        assertEquals(defaultConfig.getFileSizeKB(), testLogConfig.getFileSizeKB());
        assertEquals(defaultConfig.getFileSizeKB(), LogManager.getRootLogConfiguration().getFileSizeKB());
    }

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_all_fields_logger_config_WHEN_subscribe_THEN_correctly_reconfigures_all_loggers() throws IOException {
        Path tempRootDir2 = tempRootDir.resolve("2");
        Context context = mock(Context.class);
        Configuration configuration = mock(Configuration.class);
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
        when(configuration.lookup(anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.getRoot()).thenReturn(rootConfigTopics);
        when(kernel.getConfig()).thenReturn(configuration);
        when(kernel.getNucleusPaths()).thenReturn(nucleusPaths);
        Topics loggingConfig = Topics.of(context, NUCLEUS_CONFIG_LOGGING_TOPICS, null);
        loggingConfig.createLeafChild("level").withValue("TRACE");
        loggingConfig.createLeafChild("fileSizeKB").withValue("10");
        loggingConfig.createLeafChild("totalLogsSizeKB").withValue("1026");
        loggingConfig.createLeafChild("format").withValue("TEXT");
        loggingConfig.createLeafChild("outputType").withValue("FILE");
        loggingConfig.createLeafChild("outputDirectory").withValue(tempRootDir2.toAbsolutePath().toString());
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(loggingConfig);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY)).thenReturn(topics);
        when(configuration.lookupTopics(SYSTEM_NAMESPACE_KEY)).thenReturn(topics);
        DeviceConfiguration deviceConfiguration = new DeviceConfiguration(kernel);
        deviceConfiguration.handleLoggingConfigurationChanges(WhatHappened.childChanged, loggingConfig);

        assertEquals(Level.TRACE, LogManager.getRootLogConfiguration().getLevel());
        assertEquals(LogStore.FILE, LogManager.getRootLogConfiguration().getStore());
        assertEquals(LogFormat.TEXT, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(10, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(1026, LogManager.getRootLogConfiguration().getTotalLogStoreSizeKB());
        assertEquals(tempRootDir2.toAbsolutePath(),
                LogManager.getRootLogConfiguration().getStoreDirectory().toAbsolutePath());

        assertEquals(Level.TRACE, LogManager.getTelemetryConfig().getLevel());
        assertEquals(LogStore.FILE, LogManager.getTelemetryConfig().getStore());
        assertEquals(LogFormat.JSON, LogManager.getTelemetryConfig().getFormat());
        assertEquals(10, LogManager.getTelemetryConfig().getFileSizeKB());
        assertEquals(1026, LogManager.getTelemetryConfig().getTotalLogStoreSizeKB());
        verify(nucleusPaths, times(1)).setLoggerPath(any(Path.class));
    }

    @Test
    void GIVEN_null_logger_config_WHEN_subscribe_THEN_correctly_reconfigures_all_loggers() throws IOException {
        Configuration configuration = mock(Configuration.class);
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
        when(configuration.lookup(anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(mock(Topic.class));
        when(configuration.getRoot()).thenReturn(rootConfigTopics);
        when(kernel.getConfig()).thenReturn(configuration);
        lenient().when(kernel.getNucleusPaths()).thenReturn(nucleusPaths);
        Topics topic = mock(Topics.class);
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(topic.subscribe(childChangedArgumentCaptor.capture())).thenReturn(topic);
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(topic);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        when(configuration.lookupTopics(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME, CONFIGURATION_CONFIG_KEY)).thenReturn(topics);
        when(configuration.lookupTopics(SYSTEM_NAMESPACE_KEY)).thenReturn(topics);
        DeviceConfiguration deviceConfiguration = new DeviceConfiguration(kernel);
        deviceConfiguration.handleLoggingConfigurationChanges(WhatHappened.childChanged, null);

        childChangedArgumentCaptor.getValue().childChanged(WhatHappened.childChanged, null);

        assertEquals(Level.INFO, LogManager.getRootLogConfiguration().getLevel());
        assertEquals("greengrass", LogManager.getRootLogConfiguration().getFileName());
        assertEquals(LogStore.CONSOLE, LogManager.getRootLogConfiguration().getStore());
        assertEquals(LogFormat.TEXT, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(1024, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(10240, LogManager.getRootLogConfiguration().getTotalLogStoreSizeKB());

        assertEquals(Level.TRACE, LogManager.getTelemetryConfig().getLevel());
        assertEquals(LogStore.CONSOLE, LogManager.getTelemetryConfig().getStore());
        assertEquals(LogFormat.JSON, LogManager.getTelemetryConfig().getFormat());
        assertEquals(1024, LogManager.getTelemetryConfig().getFileSizeKB());
        assertEquals(10240, LogManager.getTelemetryConfig().getTotalLogStoreSizeKB());
        verify(nucleusPaths, times(0)).setLoggerPath(any(Path.class));
    }

    @Test
    void GIVEN_nondefault_options_on_root_logger_WHEN_create_component_logger_THEN_inherits_options() {
        LogManager
                .reconfigureAllLoggers(LogConfigUpdate.builder().level(Level.TRACE).format(LogFormat.JSON).build());
        when(mockGreengrassService.getServiceName()).thenReturn("MockService2");

        Logger logger = LogManagerHelper.getComponentLogger(mockGreengrassService);
        assertTrue(logger.isTraceEnabled());
        assertEquals(LogFormat.JSON, LogManager.getLogConfigurations().get("MockService2").getFormat());
    }

    @Test
    void loggers_created_before_or_after_log_level_change_get_the_correct_level() throws IOException {
        try (Context context = new Context()) {
            Configuration config = new Configuration(context);
            Topics logTopics = config.lookupTopics(SERVICES_NAMESPACE_TOPIC, DEFAULT_NUCLEUS_COMPONENT_NAME,
                    CONFIGURATION_CONFIG_KEY, NUCLEUS_CONFIG_LOGGING_TOPICS);
            when(kernel.getConfig()).thenReturn(config);
            lenient().when(kernel.getNucleusPaths()).thenReturn(mock(NucleusPaths.class));

            when(mockGreengrassService.getServiceName()).thenReturn("MockService3");
            Logger logger1 = LogManagerHelper.getComponentLogger(mockGreengrassService);
            assertFalse(logger1.isDebugEnabled());

            new DeviceConfiguration(kernel);

            logTopics.updateFromMap(Utils.immutableMap("level", "DEBUG"), new UpdateBehaviorTree(
                    UpdateBehaviorTree.UpdateBehavior.REPLACE, System.currentTimeMillis()));
            context.waitForPublishQueueToClear();

            when(mockGreengrassService.getServiceName()).thenReturn("MockService4");
            Logger logger2 = LogManagerHelper.getComponentLogger(mockGreengrassService);
            assertTrue(logger2.isDebugEnabled());
            assertTrue(logger1.isDebugEnabled());
        }
    }

    private static long getLogFileCount(LogConfig logConfig, String serviceName) {
        File logDirectory = logConfig.getStoreDirectory().toFile();
        return Arrays.stream(Objects.requireNonNull(
                logDirectory.listFiles(file -> file.isFile() && file.getName().startsWith(serviceName)))).count();
    }

    private static void logRandomMessages(Logger logger, int messageSize, int messageCount) {
        Random random = new Random();
        byte[] message = new byte[messageSize];
        for (int i = 0; i < messageCount; i++) {
            random.nextBytes(message);
            logger.info(base64Encoder.encodeToString(message));
        }
    }
}
