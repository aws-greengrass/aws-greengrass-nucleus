/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Configuration;
import com.aws.greengrass.config.Topic;
import com.aws.greengrass.config.Topics;
import com.aws.greengrass.dependency.Context;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogConfig;
import com.aws.greengrass.logging.impl.config.LogFormat;
import com.aws.greengrass.logging.impl.config.LogStore;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.lifecyclemanager.LogManagerHelper.NUCLEUS_CONFIG_LOGGING_TOPICS;
import static com.aws.greengrass.lifecyclemanager.LogManagerHelper.SERVICE_CONFIG_LOGGING_TOPICS;
import static com.aws.greengrass.telemetry.impl.MetricFactory.METRIC_LOGGER_PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.io.FileMatchers.aFileNamed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class LogManagerHelperTest {
    @TempDir
    protected Path tempRootDir;
    @Mock
    private GreengrassService mockGreengrassService;
    @Mock
    private Kernel kernel;

    @BeforeEach
    void setup() {
        LogManager.setRoot(tempRootDir);
    }

    @AfterEach
    void cleanup() {
        LogConfig.getInstance().reset();
        LogConfig.getInstance().closeContext();
        for (LogConfig logConfig : LogManager.getLogConfigurations().values()) {
            logConfig.reset();
            logConfig.closeContext();
        }

    }

    @BeforeAll
    static void setupLogger() {
        LogManager.getRootLogConfiguration().setStore(LogStore.FILE);
        LogManager.getTelemetryLogger(METRIC_LOGGER_PREFIX + "test");
    }
    @AfterAll
    static void cleanupLogger() {
        LogManager.getRootLogConfiguration().setStore(LogStore.CONSOLE);
    }

    @Test
    void GIVEN_mock_service_WHEN_getComponentLogger_THEN_logs_to_correct_log_file() throws IOException {
        Topics componentTopics = mock(Topics.class);
        when(mockGreengrassService.getServiceName()).thenReturn("MockService");
        when(mockGreengrassService.getConfig()).thenReturn(componentTopics);
        when(componentTopics.lookupTopics(SERVICE_CONFIG_LOGGING_TOPICS)).thenReturn(mock(Topics.class));

        Logger componentLogger = LogManagerHelper.getComponentLogger(mockGreengrassService);

        componentLogger.atInfo().log("Something");

        LogConfig logConfig = LogManager.getLogConfigurations().get("MockService");
        File logFile = new File(logConfig.getStoreName());
        assertThat(logFile, aFileNamed(equalToIgnoringCase("MockService.log")));
        assertTrue(logFile.length() > 0);
        List<String> lines = Files.readAllLines(logFile.toPath());
        assertThat(lines, hasSize(1));
        assertThat(lines.get(0), containsString("Something"));

        File ggLogFile = new File(LogManager.getRootLogConfiguration().getStoreName());
        assertThat(ggLogFile, aFileNamed(equalToIgnoringCase("greengrass.log")));
        assertEquals(0, ggLogFile.length());
    }

    @Test
    void GIVEN_all_fields_logger_config_WHEN_subscribe_THEN_correctly_reconfigures_all_loggers() {
        Configuration configuration = mock(Configuration.class);
        when(kernel.getConfig()).thenReturn(configuration);
        Map<String, String> map = new HashMap<>();
        map.put("level", "TRACE");
        map.put("fileName", "something");
        map.put("fileSizeKB", "10");
        map.put("totalLogsSizeKB", "1026");
        map.put("format", "TEXT");
        map.put("outputType", "FILE");
        map.put("outputDirectory", "/tmp/test");
        List<Map<String, String>> list = new ArrayList<>();
        list.add(map);
        Topic topic = Topic.of(mock(Context.class), NUCLEUS_CONFIG_LOGGING_TOPICS, list);
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(topic);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        LogManagerHelper.handleLoggingConfig(kernel);

        assertEquals(Level.TRACE, LogManager.getRootLogConfiguration().getLevel());
        assertEquals("something", LogManager.getRootLogConfiguration().getFileName());
        assertEquals(LogStore.FILE, LogManager.getRootLogConfiguration().getStore());
        assertEquals(LogFormat.TEXT, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(10, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(1026, LogManager.getRootLogConfiguration().getTotalLogStoreSizeKB());
        assertEquals("/tmp/test/logs", LogManager.getRootLogConfiguration().getStoreDirectory().toAbsolutePath().toString());

        assertEquals(Level.TRACE, LogManager.getTelemetryConfig().getLevel());
        assertEquals(LogStore.FILE, LogManager.getTelemetryConfig().getStore());
        assertEquals(LogFormat.TEXT, LogManager.getTelemetryConfig().getFormat());
        assertEquals(10, LogManager.getTelemetryConfig().getFileSizeKB());
        assertEquals(1026, LogManager.getTelemetryConfig().getTotalLogStoreSizeKB());
    }

    @Test
    void GIVEN_null_logger_config_WHEN_subscribe_THEN_correctly_reconfigures_all_loggers() {
        Configuration configuration = mock(Configuration.class);
        when(kernel.getConfig()).thenReturn(configuration);
        Topic topic = Topic.of(mock(Context.class), NUCLEUS_CONFIG_LOGGING_TOPICS, null);
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(topic);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        LogManagerHelper.handleLoggingConfig(kernel);

        assertEquals(Level.INFO, LogManager.getRootLogConfiguration().getLevel());
        assertEquals("greengrass", LogManager.getRootLogConfiguration().getFileName());
        assertEquals(LogStore.CONSOLE, LogManager.getRootLogConfiguration().getStore());
        assertEquals(LogFormat.JSON, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(1024, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(10240, LogManager.getRootLogConfiguration().getTotalLogStoreSizeKB());

        assertEquals(Level.INFO, LogManager.getTelemetryConfig().getLevel());
        assertEquals(LogStore.CONSOLE, LogManager.getTelemetryConfig().getStore());
        assertEquals(LogFormat.JSON, LogManager.getTelemetryConfig().getFormat());
        assertEquals(1024, LogManager.getTelemetryConfig().getFileSizeKB());
        assertEquals(10240, LogManager.getTelemetryConfig().getTotalLogStoreSizeKB());
    }

    @Test
    void GIVEN_empty_logger_config_WHEN_subscribe_THEN_correctly_reconfigures_all_loggers() {
        Configuration configuration = mock(Configuration.class);
        when(kernel.getConfig()).thenReturn(configuration);
        List<Map<String, String>> list = new ArrayList<>();
        Topic topic = Topic.of(mock(Context.class), NUCLEUS_CONFIG_LOGGING_TOPICS, list);
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookup(anyString(), anyString(), anyString(), anyString())).thenReturn(topic);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        LogManagerHelper.handleLoggingConfig(kernel);

        assertEquals(Level.INFO, LogManager.getRootLogConfiguration().getLevel());
        assertEquals("greengrass", LogManager.getRootLogConfiguration().getFileName());
        assertEquals(LogStore.CONSOLE, LogManager.getRootLogConfiguration().getStore());
        assertEquals(LogFormat.JSON, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(1024, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(10240, LogManager.getRootLogConfiguration().getTotalLogStoreSizeKB());

        assertEquals(Level.INFO, LogManager.getTelemetryConfig().getLevel());
        assertEquals(LogStore.CONSOLE, LogManager.getTelemetryConfig().getStore());
        assertEquals(LogFormat.JSON, LogManager.getTelemetryConfig().getFormat());
        assertEquals(1024, LogManager.getTelemetryConfig().getFileSizeKB());
        assertEquals(10240, LogManager.getTelemetryConfig().getTotalLogStoreSizeKB());
    }
}
