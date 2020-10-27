/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.aws.greengrass.lifecyclemanager;

import com.aws.greengrass.config.Topics;
import com.aws.greengrass.logging.api.Logger;
import com.aws.greengrass.logging.impl.LogManager;
import com.aws.greengrass.logging.impl.config.LogConfig;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.aws.greengrass.lifecyclemanager.LogManagerHelper.SERVICE_CONFIG_LOGGING_TOPICS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.io.FileMatchers.aFileNamed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, GGExtension.class})
class LogManagerHelperTest {
    @TempDir
    protected Path tempRootDir;
    @Mock
    private GreengrassService mockGreengrassService;

    @BeforeEach
    void setup() {
        LogManager.setRoot(tempRootDir);
    }

    @AfterEach
    void cleanup() {
        LogConfig.getInstance().closeContext();
        for (LogConfig logConfig : LogManager.getLogConfigurations().values()) {
            logConfig.closeContext();
        }
    }

    @BeforeAll
    static void setupLogger() {
        LogManager.getRootLogConfiguration().setStore(LogStore.FILE);
    }
    @AfterAll
    static void cleanupLogger() {
        LogManager.getRootLogConfiguration().setStore(LogStore.CONSOLE);
    }

    @Test
    void GIVEN_mock_service_WHEN_getComponentLogger_THEN_logs_to_correct_log_file() throws IOException {
        //Configuration kernelConfig = mock(Configuration.class);
        Topics componentTopics = mock(Topics.class);
        when(mockGreengrassService.getServiceName()).thenReturn("MockService");
        when(mockGreengrassService.getConfig()).thenReturn(componentTopics);
        //when(mockKernel.getConfig()).thenReturn(kernelConfig);
        //when(kernelConfig.lookup(SERVICES_NAMESPACE_TOPIC, "", KERNEL_CONFIG_LOGGING_TOPICS))
        //        .thenReturn(mock(Topic.class));
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
}
