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
import com.aws.greengrass.logging.impl.config.model.LoggerConfiguration;
import com.aws.greengrass.testcommons.testutilities.GGExtension;
import com.aws.greengrass.util.NucleusPaths;
import com.aws.greengrass.util.Utils;
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
import java.util.List;

import static com.aws.greengrass.componentmanager.KernelConfigResolver.CONFIGURATION_CONFIG_KEY;
import static com.aws.greengrass.deployment.DeviceConfiguration.DEFAULT_NUCLEUS_COMPONENT_NAME;
import static com.aws.greengrass.deployment.DeviceConfiguration.NUCLEUS_CONFIG_LOGGING_TOPICS;
import static com.aws.greengrass.lifecyclemanager.GreengrassService.SERVICES_NAMESPACE_TOPIC;
import static com.aws.greengrass.telemetry.impl.MetricFactory.METRIC_LOGGER_PREFIX;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.io.FileMatchers.aFileNamed;
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

        LogConfig.getInstance().setStore(LogStore.FILE);
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

    @SuppressWarnings("PMD.CloseResource")
    @Test
    void GIVEN_all_fields_logger_config_WHEN_subscribe_THEN_correctly_reconfigures_all_loggers() throws IOException {
        Context context = mock(Context.class);
        Configuration configuration = mock(Configuration.class);
        NucleusPaths nucleusPaths = mock(NucleusPaths.class);
        Topics rootConfigTopics = mock(Topics.class);
        when(rootConfigTopics.findOrDefault(any(), anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
        when(configuration.lookup(anyString(), anyString())).thenReturn(mock(Topic.class));
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
        loggingConfig.createLeafChild("outputDirectory").withValue("/tmp/test");
        Topics topics = Topics.of(mock(Context.class), SERVICES_NAMESPACE_TOPIC, mock(Topics.class));
        when(configuration.lookupTopics(anyString(), anyString(), anyString(), anyString())).thenReturn(loggingConfig);
        when(configuration.lookupTopics(anyString())).thenReturn(topics);
        DeviceConfiguration deviceConfiguration = new DeviceConfiguration(kernel);
        deviceConfiguration.handleLoggingConfigurationChanges(WhatHappened.childChanged, loggingConfig);

        assertEquals(Level.TRACE, LogManager.getRootLogConfiguration().getLevel());
        assertEquals(LogStore.FILE, LogManager.getRootLogConfiguration().getStore());
        assertEquals(LogFormat.TEXT, LogManager.getRootLogConfiguration().getFormat());
        assertEquals(10, LogManager.getRootLogConfiguration().getFileSizeKB());
        assertEquals(1026, LogManager.getRootLogConfiguration().getTotalLogStoreSizeKB());
        assertEquals("/tmp/test", LogManager.getRootLogConfiguration().getStoreDirectory().toAbsolutePath().toString());

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
        when(configuration.lookup(anyString(), anyString())).thenReturn(mock(Topic.class));
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
                .reconfigureAllLoggers(LoggerConfiguration.builder().level(Level.TRACE).format(LogFormat.JSON).build());
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
}
